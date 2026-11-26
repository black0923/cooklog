package com.cooklog.kitchen;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 调用智谱免费模型，从做法步骤里提取配料；原生发请求，绕开 WebView 的跨域限制。 */
@CapacitorPlugin(name = "Ai")
public class AiPlugin extends Plugin {

    private static final String API = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    @PluginMethod
    public void extractIngredients(PluginCall call) {
        String text = call.getString("text", "");
        if (text == null || text.trim().isEmpty()) {
            call.reject("步骤是空的");
            return;
        }
        final String input = text.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String content = ask(input);
                    JSONArray arr = parseArray(content);
                    JSArray out = new JSArray();
                    int n = Math.min(arr.length(), 30);
                    for (int i = 0; i < n; i++) {
                        String s = arr.optString(i, "").trim();
                        if (!s.isEmpty() && s.length() <= 16) out.put(s);
                    }
                    JSObject ret = new JSObject();
                    ret.put("items", out);
                    call.resolve(ret);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    call.reject(msg == null ? e.toString() : msg);
                }
            }
        }).start();
    }

    private static String ask(String steps) throws Exception {
        String key = AiKey.ZHIPU;
        if (key == null || key.trim().isEmpty()) throw new Exception("没有配置 AI Key");

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content",
                "你是菜谱助手。从做菜步骤中提取真正会吃进去的食材名称，只输出一个 JSON 数组，元素是食材中文名。" +
                "不要带数量、单位、动作、厨具、口味词；重复的只留一个；没有食材就输出 []。" +
                "特别注意：任何水类液体都不是食材，绝对不要输出，包括热水、清水、开水、温水、冷水、凉水、冰水、凉白开、纯净水、矿泉水，以及香菇水、葱姜水、花椒水等任何以「水」结尾的液体；水只是加热或清洗用的介质。" +
                "示例：输入「洋葱切丝，鸡肉切块，加热水没过食材，加盐和生抽炒熟」输出 [\"洋葱\",\"鸡肉\",\"盐\",\"生抽\"]");
        messages.put(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", steps);
        messages.put(usr);

        JSONObject body = new JSONObject();
        body.put("model", "glm-4-flash");
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", 300);
        body.put("stream", false);

        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key.trim());
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.close();

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = read(in);
        conn.disconnect();
        if (code != 200) {
            String detail = "";
            try {
                JSONObject root = new JSONObject(resp);
                JSONObject err = root.optJSONObject("error");
                if (err != null) detail = err.optString("message", "");
            } catch (Exception ignored) {
            }
            if (code == 429) throw new Exception("请求太频繁，稍后再试");
            throw new Exception("接口返回 " + code + (detail.isEmpty() ? "" : "：" + detail));
        }
        JSONObject root = new JSONObject(resp);
        return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private static JSONArray parseArray(String content) throws Exception {
        String s = content == null ? "" : content.trim();
        int a = s.indexOf('[');
        int b = s.lastIndexOf(']');
        if (a < 0 || b <= a) throw new IllegalArgumentException("没解析出配料列表");
        return new JSONArray(s.substring(a, b + 1));
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
