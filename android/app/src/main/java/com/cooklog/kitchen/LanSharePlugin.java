package com.cooklog.kitchen;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 局域网共选：在本机起一个只读的小 HTTP 服务，让同一 WiFi（或本机热点）下的好友
 * 用浏览器打开页面，从主人的菜里挑选想吃的，结果回传给 App。
 *
 * 路由统一带访问码前缀，避免同网段其他设备瞎猜地址：
 *   GET  /{code}/            选菜页面
 *   GET  /{code}/api/data    菜品清单 JSON
 *   GET  /{code}/img/{i}     第 i 道菜的缩略图
 *   POST /{code}/submit      好友提交选择
 */
@CapacitorPlugin(name = "LanShare")
public class LanSharePlugin extends Plugin {

    private static final int BASE_PORT = 8137;

    private LanServer server;
    private String code = "";
    /** 好友端页面由 JS 传入，热更新后不必依赖安装包里的旧副本 */
    private volatile String guestHtml = "";
    private final List<Dish> dishes = new ArrayList<>();
    private final List<Pick> picks = new ArrayList<>();

    private static class Dish {
        String name = "";
        String ings = "";
        int rating = 0;
        byte[] thumb;
    }

    private static class Pick {
        long at;
        List<String> names = new ArrayList<>();
    }

    @PluginMethod
    public void start(PluginCall call) {
        String gh = call.getString("html", "");
        this.guestHtml = gh == null ? "" : gh;
        JSArray arr = call.getArray("dishes");
        dishes.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Dish d = new Dish();
                d.name = o.optString("name", "");
                d.ings = o.optString("ings", "");
                d.rating = o.optInt("rating", 0);
                String b64 = o.optString("thumb", "");
                if (!b64.isEmpty()) {
                    try {
                        d.thumb = Base64.decode(b64, Base64.DEFAULT);
                    } catch (Exception ignored) {
                    }
                }
                if (!d.name.isEmpty()) dishes.add(d);
            }
        }
        if (dishes.isEmpty()) {
            call.reject("没有可以分享的菜");
            return;
        }

        stopServer();

        String wanted = call.getString("code", "");
        code = (wanted == null || wanted.isEmpty()) ? randomCode() : wanted;

        Exception last = null;
        for (int p = BASE_PORT; p < BASE_PORT + 12; p++) {
            try {
                LanServer s = new LanServer(p);
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                server = s;
                last = null;
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (server == null) {
            call.reject("无法开启服务：" + (last == null ? "端口被占用" : last.getMessage()));
            return;
        }

        String ip = localIp();
        JSObject ret = new JSObject();
        ret.put("ip", ip == null ? "" : ip);
        ret.put("port", server.getListeningPort());
        ret.put("code", code);
        ret.put("url", ip == null ? "" : "http://" + ip + ":" + server.getListeningPort() + "/" + code + "/");
        ret.put("count", dishes.size());
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopServer();
        call.resolve();
    }

    @PluginMethod
    public void getPicks(PluginCall call) {
        JSArray out = new JSArray();
        for (Pick p : picks) {
            JSObject o = new JSObject();
            o.put("at", p.at);
            JSArray names = new JSArray();
            for (String n : p.names) names.put(n);
            o.put("picks", names);
            out.put(o);
        }
        JSObject ret = new JSObject();
        ret.put("running", server != null);
        ret.put("submissions", out);
        call.resolve(ret);
    }

    @PluginMethod
    public void clearPicks(PluginCall call) {
        picks.clear();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        // Activity 销毁时一定要关掉监听，否则下次进来端口会被旧实例占着
        stopServer();
        super.handleOnDestroy();
    }

    private void stopServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
            server = null;
        }
    }

    private static String randomCode() {
        String s = "";
        for (int i = 0; i < 4; i++) s += (int) (Math.random() * 10);
        return s;
    }

    private String localIp() {
        // 1) 先问系统：WiFi / 热点的链路地址最准，能避开 VPN、蜂窝和数据网络
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                String eth = null;
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (nc == null || lp == null) continue;
                    boolean wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    boolean wired = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!wifi && !wired) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                        if (wifi) return a.getHostAddress();
                        if (wired && eth == null) eth = a.getHostAddress();
                    }
                }
                if (eth != null) return eth;
            }
        } catch (Exception ignored) {
        }

        // 2) 兜底：自己枚举网卡
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            String best = null;
            int bestScore = -1;
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String nm = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (nm.startsWith("rmnet") || nm.startsWith("ccmni") || nm.startsWith("p2p")
                        || nm.startsWith("dummy") || nm.startsWith("tun")) {
                    continue;
                }
                int score = 1;
                if (nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan") || nm.contains("softap")) {
                    score = 5;
                } else if (nm.startsWith("eth")) {
                    score = 3;
                }
                List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
                for (InterfaceAddress ia : addrs) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (score > bestScore) {
                            bestScore = score;
                            best = a.getHostAddress();
                        }
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** 自己访问一次 http://127.0.0.1，确认服务真的在跑 */
    @PluginMethod
    public void selfTest(final PluginCall call) {
        final LanServer s = server;
        if (s == null) {
            call.reject("服务没有在运行");
            return;
        }
        final int port = s.getListeningPort();
        final String c = code;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 页面和接口都要测：只测接口会漏掉「页面路由自己重定向到自己」这类问题
                    int page = httpStatus(port, "/" + c + "/");
                    int api = httpStatus(port, "/" + c + "/api/data");
                    JSObject ret = new JSObject();
                    ret.put("ok", page == 200 && api == 200);
                    ret.put("status", page);
                    ret.put("apiStatus", api);
                    ret.put("port", port);
                    if (page != 200) ret.put("msg", "选菜页面返回 " + page);
                    else if (api != 200) ret.put("msg", "菜品接口返回 " + api);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }).start();
    }

    private static int httpStatus(int port, String path) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + path);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 交给系统浏览器打开，省得用户复制地址再粘贴 */
    @PluginMethod
    public void openInBrowser(PluginCall call) {
        String u = call.getString("url", "");
        if (u == null || u.isEmpty()) {
            call.reject("地址为空");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("没有可用的浏览器");
        }
    }

    /* ---------------- HTTP 服务 ---------------- */

    private class LanServer extends NanoHTTPD {

        LanServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                String uri = session.getUri();
                if (uri == null) uri = "/";
                int q = uri.indexOf('?');
                if (q >= 0) uri = uri.substring(0, q);
                String[] seg = uri.split("/");
                // Java 的 split 会丢掉结尾空串："/1234/" 和 "/1234" 都是 length=2。
                // 必须按原始 URI 是否带斜杠区分，否则 "/1234/" 会重定向到自己，浏览器死循环。
                if (seg.length == 2 && code.equals(seg[1])) {
                    if (uri.endsWith("/")) {
                        return cors(html());
                    }
                    Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    r.addHeader("Location", "/" + code + "/");
                    return cors(r);
                }
                if (seg.length < 2 || !code.equals(seg[1])) {
                    return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found"));
                }
                String head = seg[2];
                if ("api".equals(head) && seg.length >= 4 && "data".equals(seg[3])) {
                    return cors(json());
                }
                if ("img".equals(head) && seg.length >= 4) {
                    return cors(image(seg[3]));
                }
                if ("submit".equals(head) && Method.POST.equals(session.getMethod())) {
                    return cors(submit(session));
                }
                return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found"));
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error"));
            }
        }

        private Response html() {
            String cached = guestHtml;
            if (cached != null && !cached.isEmpty()) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", cached);
            }
            try {
                InputStream in = getContext().getAssets().open("public/lan.html");
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                in.close();
                String s = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s);
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                        "页面缺失，请更新 App");
            }
        }

        private Response json() {
            try {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < dishes.size(); i++) {
                    Dish d = dishes.get(i);
                    JSONObject o = new JSONObject();
                    o.put("name", d.name);
                    o.put("ings", d.ings);
                    o.put("rating", d.rating);
                    o.put("hasImg", d.thumb != null && d.thumb.length > 0);
                    arr.put(o);
                }
                JSONObject root = new JSONObject();
                root.put("dishes", arr);
                return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                        root.toString());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "{}");
            }
        }

        private Response image(String idx) {
            try {
                int i = Integer.parseInt(idx);
                if (i < 0 || i >= dishes.size()) throw new Exception("out of range");
                byte[] b = dishes.get(i).thumb;
                if (b == null || b.length == 0) throw new Exception("no image");
                Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                        new ByteArrayInputStream(b), b.length);
                r.addHeader("Cache-Control", "max-age=3600");
                return r;
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no image");
            }
        }

        private Response submit(IHTTPSession session) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if (body == null || body.isEmpty()) throw new Exception("empty");
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.optJSONArray("picks");
                if (arr == null || arr.length() == 0) throw new Exception("empty picks");
                Pick p = new Pick();
                p.at = System.currentTimeMillis();
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, "");
                    if (!n.isEmpty()) p.names.add(n);
                }
                // 同一个人反复改，以最后一次为准：清掉 30 秒内上一份
                if (!picks.isEmpty()) {
                    Pick prev = picks.get(picks.size() - 1);
                    if (p.at - prev.at < 30000) picks.remove(picks.size() - 1);
                }
                picks.add(p);
                JSONObject ret = new JSONObject();
                ret.put("ok", true);
                return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", ret.toString());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8",
                        "{\"ok\":false}");
            }
        }

        private Response cors(Response r) {
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Access-Control-Allow-Headers", "Content-Type");
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            r.addHeader("Cache-Control", "no-store");
            return r;
        }
    }
}
