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

    private static final String SYS_ING =
            "你是菜谱助手。从做菜步骤中提取真正会吃进去的食材名称，只输出一个 JSON 数组，元素是食材中文名。" +
            "不要带数量、单位、动作、厨具、口味词；重复的只留一个；没有食材就输出 []。" +
            "特别注意：任何水类液体都不是食材，绝对不要输出，包括热水、清水、开水、温水、冷水、凉水、冰水、凉白开、纯净水、矿泉水，以及香菇水、葱姜水、花椒水等任何以「水」结尾的液体；水只是加热或清洗用的介质。" +
            "示例：输入「洋葱切丝，鸡肉切块，加热水没过食材，加盐和生抽炒熟」输出 [\"洋葱\",\"鸡肉\",\"盐\",\"生抽\"]";

    private static final String SYS_RECO =
            "你是家常菜顾问。根据今天的日期、季节和给出的已有菜品，推荐 4 道今天适合吃的家常菜。" +
            "优先考虑当季食材、季节气温特点、营养均衡、荤素搭配和下厨难度。" +
            "如果给出了实时气温，以实时气温为准而不是只看季节名称：气温高时优先清淡、少油、瓜类和凉拌，气温低时优先热汤、炖煮和根茎类。" +
            "优先从已有菜品里挑 2 到 3 道，再判断菜谱里明显缺什么类别（比如绿叶菜、汤、清淡菜），可以额外推荐 1 到 2 道没有的菜。" +
            "只输出一个 JSON 数组，不要解释文字，元素形如 {\"name\":\"菜名\",\"reason\":\"不超过20字的推荐理由\",\"known\":true}。" +
            "known=true 表示这道菜在已有菜品里，name 必须和已有菜名完全一致；known=false 表示这是新推荐的菜。" +
            "不要重复，不要输出水类液体，不要推荐需要特殊厨具或难买食材的菜。";

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
                    String content = ask(SYS_ING, input);
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

    @PluginMethod
    public void recommend(PluginCall call) {
        String dishes = call.getString("dishes", "");
        String date = call.getString("date", "");
        String season = call.getString("season", "");
        String exclude = call.getString("exclude", "");
        if (dishes == null || dishes.trim().isEmpty()) {
            call.reject("还没有可以推荐的菜");
            return;
        }
        StringBuilder payload = new StringBuilder();
        payload.append("今天是 ").append(date == null ? "" : date).append("（")
                .append(season == null ? "" : season).append("）。");
        payload.append("已有菜品及主要配料：").append(dishes.trim());
        if (exclude != null && !exclude.trim().isEmpty()) {
            payload.append("。上一批推荐过：").append(exclude.trim()).append("，这次尽量换几道。");
        }
        final String input = payload.toString();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String content = ask(SYS_RECO, input);
                    JSONArray arr = parseArray(content);
                    JSArray out = new JSArray();
                    int n = Math.min(arr.length(), 6);
                    for (int i = 0; i < n; i++) {
                        String name = "";
                        String reason = "";
                        boolean known = false;
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            name = o.optString("name", "").trim();
                            reason = o.optString("reason", "").trim();
                            known = o.optBoolean("known", false);
                        } else {
                            name = arr.optString(i, "").trim();
                            known = true;
                        }
                        if (name.isEmpty() || name.length() > 20) continue;
                        if (reason.length() > 40) reason = reason.substring(0, 40);
                        JSObject item = new JSObject();
                        item.put("name", name);
                        item.put("reason", reason);
                        item.put("known", known);
                        out.put(item);
                    }
                    if (out.length() == 0) throw new Exception("没解析出推荐结果");
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

    private static String ask(String system, String userText) throws Exception {
        String key = AiKey.ZHIPU;
        if (key == null || key.trim().isEmpty()) throw new Exception("没有配置 AI Key");

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", system);
        messages.put(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", userText);
        messages.put(usr);

        JSONObject body = new JSONObject();
        body.put("model", "glm-4-flash");
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", 600);
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
