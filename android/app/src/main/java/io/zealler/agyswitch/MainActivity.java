package io.zealler.agyswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agy Switch Android 端：管理多个 Antigravity 账号、查额度、一键切换 agy CLI 当前账号。
 * 全部功能在这一个文件里：手写 HTTP（HttpURLConnection）+ 系统 org.json，无第三方库。
 * token 只存应用私有目录，不做任何网络上传（除 Google 官方接口本身）。
 */
public class MainActivity extends Activity {

    // ===== 协议常量（与桌面端 agy-switch 上游一致） =====
    private static final String CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    // 构建前由脚本从上游 oauth.rs 提取并替换此占位符
    private static final String CLIENT_SECRET = "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";

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String QUOTA_URL =
            "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary";
    private static final String LOAD_CODE_ASSIST_URL =
            "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist";
    private static final String USER_AGENT = "vscode/1.96.2 (Antigravity/4.10.16)";

    private static final String TERMUX_TOKEN_PATH =
            "/data/data/com.termux/files/home/.gemini/antigravity-cli/antigravity-oauth-token";
    private static final String TERMUX_TOKEN_DIR =
            "/data/data/com.termux/files/home/.gemini/antigravity-cli";

    private static final int REQ_IMPORT_DESKTOP = 1001;
    private static final int REQ_IMPORT_TOKEN_FILE = 1002;
    private static final int REQ_EXPORT_ACCOUNTS = 1003;
    private static final int REQ_SAVE_TOKEN_FILE = 1004;

    // ===== 账号模型 =====
    static class Account {
        String id;
        String email = "";
        String name = "";
        String refreshToken = "";
        String accessToken = "";
        long expiryTs = 0;              // access token 过期时间（毫秒）
        String idToken = "";            // 最近一次刷新拿到的 id_token（可空）
        String projectId = "";
        String customLabel = "";
        String tier = "";               // 订阅档，如 Google AI Pro
        JSONObject quota = new JSONObject(); // 额度缓存
        long quotaUpdatedAt = 0;
        String lastError = "";
        long createdAt = 0;
        long lastUsed = 0;

        String displayName() {
            if (customLabel != null && !customLabel.isEmpty()) return customLabel;
            if (email != null && !email.isEmpty()) return email;
            return "(未命名账号)";
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("email", email);
            o.put("name", name);
            o.put("refresh_token", refreshToken);
            o.put("access_token", accessToken);
            o.put("expiry_timestamp", expiryTs);
            o.put("id_token", idToken);
            o.put("project_id", projectId);
            o.put("custom_label", customLabel);
            o.put("subscription_tier", tier);
            o.put("quota", quota == null ? new JSONObject() : quota);
            o.put("quota_updated_at", quotaUpdatedAt);
            o.put("created_at", createdAt);
            o.put("last_used", lastUsed);
            return o;
        }

        static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id", "");
            a.email = o.optString("email", "");
            a.name = o.optString("name", "");
            a.refreshToken = o.optString("refresh_token", "");
            a.accessToken = o.optString("access_token", "");
            a.expiryTs = o.optLong("expiry_timestamp", 0);
            a.idToken = o.optString("id_token", "");
            a.projectId = o.optString("project_id", "");
            a.customLabel = o.optString("custom_label", "");
            a.tier = o.optString("subscription_tier", "");
            a.quota = o.optJSONObject("quota");
            if (a.quota == null) a.quota = new JSONObject();
            a.quotaUpdatedAt = o.optLong("quota_updated_at", 0);
            a.createdAt = o.optLong("created_at", 0);
            a.lastUsed = o.optLong("last_used", 0);
            if (a.id.isEmpty()) a.id = "acc_" + a.createdAt + "_" + Math.abs(a.email.hashCode());
            return a;
        }
    }

    static class HttpResult {
        int code;
        String body;
    }

    private final List<Account> accounts = new ArrayList<>();
    private String currentAccountId = "";
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private LinearLayout listContainer;
    private TextView statusText;
    private String pendingTokenContent = ""; // 无 root 时待保存的 token 文件内容

    // ===== 生命周期 =====
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadAccounts();
        buildUi();
        rebuildList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ===== UI 搭建（纯代码，无资源文件） =====
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Agy Switch（Antigravity 账号切换）");
        title.setTextSize(18);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(makeButton("添加账号", v -> showAddAccountDialog()), wrapParams());
        btnRow.addView(makeButton("全部刷新", v -> refreshAll()), wrapParams());
        btnRow.addView(makeButton("导出全部", v -> startExport()), wrapParams());
        root.addView(btnRow);

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private LinearLayout.LayoutParams wrapParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(8), dp(8));
        return p;
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void rebuildList() {
        listContainer.removeAllViews();
        statusText.setText("共 " + accounts.size() + " 个账号，数据只存本机应用私有目录");
        if (accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有账号。点「添加账号」，粘贴 refresh token 或导入桌面端导出文件。");
            empty.setPadding(0, dp(16), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (Account a : accounts) {
            listContainer.addView(buildAccountCard(a));
        }
    }

    private View buildAccountCard(final Account a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cp);
        card.setBackgroundColor(0x22888888);

        boolean isCurrent = a.id != null && a.id.equals(currentAccountId);

        TextView head = new TextView(this);
        head.setTextSize(15);
        head.setText((isCurrent ? "★ 当前  " : "") + a.displayName());
        card.addView(head);

        TextView detail = new TextView(this);
        detail.setTextSize(13);
        StringBuilder sb = new StringBuilder();
        if (!a.email.isEmpty() && !a.email.equals(a.displayName())) {
            sb.append("邮箱：").append(a.email).append("\n");
        }
        if (!a.name.isEmpty()) sb.append("名称：").append(a.name).append("\n");
        sb.append("订阅档：").append(a.tier.isEmpty() ? "未查" : a.tier).append("\n");
        sb.append(quotaLine(a, "gemini"));
        sb.append(quotaLine(a, "claude"));
        if (a.quotaUpdatedAt > 0) {
            sb.append("额度更新于：").append(formatTime(a.quotaUpdatedAt)).append("\n");
        }
        if (!a.lastError.isEmpty()) {
            sb.append("⚠ ").append(a.lastError);
        }
        detail.setText(sb.toString().trim());
        card.addView(detail);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.addView(makeButton("刷新额度", v -> refreshAccount(a, true)), wrapParams());
        row.addView(makeButton("设为当前", v -> switchToAccount(a)), wrapParams());
        row.addView(makeButton("备注", v -> showEditLabelDialog(a)), wrapParams());
        row.addView(makeButton("删除", v -> confirmDelete(a)), wrapParams());
        card.addView(row);

        return card;
    }

    /** 生成一行额度文字：周额度 / 5 小时额度 / 重置时间 */
    private String quotaLine(Account a, String which) {
        try {
            JSONObject q = a.quota;
            if (q == null || !q.has(which + "_weekly")) {
                return ("gemini".equals(which) ? "Gemini" : "Claude/GPT") + "：未查\n";
            }
            double weekly = q.optDouble(which + "_weekly", -1);
            double h5 = q.optDouble(which + "_5h", -1);
            String reset = q.optString(which + "_weekly_reset", "");
            StringBuilder sb = new StringBuilder();
            sb.append("gemini".equals(which) ? "Gemini" : "Claude/GPT");
            sb.append(" 周额度剩余 ").append(pct(weekly)).append("%");
            if (h5 >= 0) sb.append("，5 小时剩余 ").append(pct(h5)).append("%");
            if (!reset.isEmpty()) sb.append("，重置 ").append(shortTime(reset));
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int pct(double fraction) {
        if (fraction < 0) return 0;
        return (int) Math.round(fraction * 100.0);
    }

    private String shortTime(String rfc3339) {
        // 2026-01-01T00:00:00Z -> 01-01 00:00
        try {
            String s = rfc3339.replace("T", " ");
            if (s.length() > 16) s = s.substring(0, 16);
            if (s.length() > 5) s = s.substring(5);
            return s;
        } catch (Exception e) {
            return rfc3339;
        }
    }

    private String formatTime(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return f.format(new Date(ts));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ===== 本地存储：应用私有文件 accounts.json =====
    private File storeFile() {
        return new File(getFilesDir(), "accounts.json");
    }

    private void loadAccounts() {
        accounts.clear();
        try {
            File f = storeFile();
            if (!f.exists()) return;
            String text = readAll(new FileInputStream(f));
            JSONObject root = new JSONObject(text);
            currentAccountId = root.optString("current_account_id", "");
            JSONArray arr = root.optJSONArray("accounts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) accounts.add(Account.fromJson(o));
                }
            }
        } catch (Exception e) {
            // 文件损坏时不闪退，当作空列表
            accounts.clear();
        }
    }

    private void saveAccounts() {
        try {
            JSONObject root = new JSONObject();
            root.put("current_account_id", currentAccountId);
            JSONArray arr = new JSONArray();
            for (Account a : accounts) arr.put(a.toJson());
            root.put("accounts", arr);
            FileOutputStream fos = new FileOutputStream(storeFile());
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    // ===== HTTP 基础 =====
    private HttpResult httpPostForm(String urlStr, String formBody) throws Exception {
        return httpRequest(urlStr, "POST", formBody.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", null);
    }

    private HttpResult httpPostJson(String urlStr, String jsonBody, String accessToken) throws Exception {
        return httpRequest(urlStr, "POST", jsonBody.getBytes(StandardCharsets.UTF_8),
                "application/json", accessToken);
    }

    private HttpResult httpGet(String urlStr, String accessToken) throws Exception {
        return httpRequest(urlStr, "GET", null, null, accessToken);
    }

    private HttpResult httpRequest(String urlStr, String method, byte[] body,
                                   String contentType, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (accessToken != null && !accessToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            conn.setDoOutput(true);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        HttpResult r = new HttpResult();
        r.code = code;
        r.body = in != null ? readAll(in) : "";
        conn.disconnect();
        return r;
    }

    private String formEncode(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    // ===== 账号业务：刷新 token / 用户信息 / 订阅档 / 额度 =====

    /** 用 refresh token 换新的 access token，写回账号字段。失败抛异常（带 HTTP 状态码）。 */
    private void refreshAccessToken(Account a) throws Exception {
        String form = formEncode(
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET,
                "refresh_token", a.refreshToken,
                "grant_type", "refresh_token");
        HttpResult r = httpPostForm(TOKEN_URL, form);
        if (r.code != 200) {
            throw new Exception("刷新 token 失败 HTTP " + r.code + "：" + brief(r.body));
        }
        JSONObject o = new JSONObject(r.body);
        a.accessToken = o.optString("access_token", "");
        if (a.accessToken.isEmpty()) throw new Exception("刷新 token 响应里没有 access_token");
        long expiresIn = o.optLong("expires_in", 3600);
        a.expiryTs = System.currentTimeMillis() + expiresIn * 1000L;
        String idToken = o.optString("id_token", "");
        if (!idToken.isEmpty()) a.idToken = idToken;
    }

    /** 拉 userinfo 补邮箱和名称 */
    private void fetchUserInfo(Account a) throws Exception {
        HttpResult r = httpGet(USERINFO_URL, a.accessToken);
        if (r.code != 200) {
            throw new Exception("拉用户信息失败 HTTP " + r.code + "：" + brief(r.body));
        }
        JSONObject o = new JSONObject(r.body);
        String email = o.optString("email", "");
        if (!email.isEmpty()) a.email = email;
        String name = o.optString("name", "");
        if (!name.isEmpty()) a.name = name;
    }

    /** loadCodeAssist：拿 project_id 与订阅档。失败不抛异常，只记录（不阻塞额度）。 */
    private void fetchTierAndProject(Account a) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("ideType", "ANTIGRAVITY");
            meta.put("platform", "PLATFORM_UNSPECIFIED");
            meta.put("pluginType", "GEMINI");
            JSONObject body = new JSONObject();
            body.put("metadata", meta);
            body.put("mode", "PROJECT");
            HttpResult r = httpPostJson(LOAD_CODE_ASSIST_URL, body.toString(), a.accessToken);
            if (r.code != 200) return;
            JSONObject o = new JSONObject(r.body);
            // cloudaicompanionProject 可能是字符串，也可能是 {id: "..."}
            Object proj = o.opt("cloudaicompanionProject");
            if (proj instanceof String && !((String) proj).isEmpty()) {
                a.projectId = (String) proj;
            } else if (proj instanceof JSONObject) {
                String pid = ((JSONObject) proj).optString("id", "");
                if (!pid.isEmpty()) a.projectId = pid;
            }
            JSONObject paid = o.optJSONObject("paidTier");
            JSONObject cur = o.optJSONObject("currentTier");
            String tierName = "";
            if (paid != null) tierName = paid.optString("name", "");
            if (tierName.isEmpty() && cur != null) tierName = cur.optString("name", "");
            if (!tierName.isEmpty()) a.tier = tierName;
        } catch (Exception ignored) {
            // 订阅档拿不到不阻塞主流程
        }
    }

    /** retrieveUserQuotaSummary：解析两个分组的周额度与 5 小时额度，缓存进账号 */
    private void fetchQuota(Account a) throws Exception {
        String project = (a.projectId != null && !a.projectId.isEmpty())
                ? a.projectId : "aicode-consumers";
        JSONObject body = new JSONObject();
        body.put("project", project);
        // 与上游一致：三个额度端点依次尝试，任一成功即可
        String[] quotaUrls = {
                QUOTA_URL,
                "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
                "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary"
        };
        HttpResult r = null;
        Exception lastErr = null;
        for (String u : quotaUrls) {
            try {
                r = httpPostJson(u, body.toString(), a.accessToken);
                if (r.code == 200) break;
            } catch (Exception e) {
                lastErr = e;
                r = null;
            }
        }
        if (r == null) {
            throw new Exception("查额度网络失败：" + (lastErr != null ? lastErr.getMessage() : "未知"));
        }
        if (r.code != 200) {
            throw new Exception("查额度失败 HTTP " + r.code + "：" + brief(r.body));
        }
        JSONObject o = new JSONObject(r.body);
        JSONArray groups = o.optJSONArray("groups");
        JSONObject cache = new JSONObject();
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                String display = g.optString("displayName", "");
                String key;
                if (display.toLowerCase(Locale.US).contains("gemini")) key = "gemini";
                else if (display.toLowerCase(Locale.US).contains("claude")) key = "claude";
                else continue;
                JSONArray buckets = g.optJSONArray("buckets");
                if (buckets == null) continue;
                for (int j = 0; j < buckets.length(); j++) {
                    JSONObject b = buckets.optJSONObject(j);
                    if (b == null) continue;
                    String bucketId = b.optString("bucketId", "");
                    double frac = b.optDouble("remainingFraction", -1);
                    String reset = b.optString("resetTime", "");
                    if (bucketId.contains("weekly")) {
                        cache.put(key + "_weekly", frac);
                        cache.put(key + "_weekly_reset", reset);
                        cache.put(key + "_window", b.optString("window", "weekly"));
                    } else if (bucketId.contains("5h")) {
                        cache.put(key + "_5h", frac);
                        cache.put(key + "_5h_reset", reset);
                    }
                }
            }
        }
        a.quota = cache;
        a.quotaUpdatedAt = System.currentTimeMillis();
    }

    private String brief(String body) {
        if (body == null) return "";
        String s = body.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** 完整刷新一个账号：换 token -> 订阅档/项目 -> 额度。成功返回 true。 */
    private boolean doRefresh(Account a) {
        try {
            refreshAccessToken(a);
            if (a.email.isEmpty()) {
                try { fetchUserInfo(a); } catch (Exception ignored) { }
            }
            fetchTierAndProject(a);
            fetchQuota(a);
            a.lastError = "";
            return true;
        } catch (Exception e) {
            a.lastError = "刷新失败：" + e.getMessage();
            return false;
        }
    }

    private void refreshAccount(final Account a, boolean showToast) {
        toast("正在刷新 " + a.displayName() + " …");
        executor.execute(() -> {
            boolean ok = doRefresh(a);
            saveAccounts();
            runOnUiThread(() -> {
                rebuildList();
                if (showToast) toast(ok ? "已刷新 " + a.displayName()
                        : a.displayName() + " " + a.lastError);
            });
        });
    }

    private void refreshAll() {
        if (accounts.isEmpty()) { toast("还没有账号"); return; }
        toast("正在逐个刷新全部账号…");
        executor.execute(() -> {
            int okCount = 0;
            for (Account a : accounts) {
                if (doRefresh(a)) okCount++;
                saveAccounts();
                runOnUiThread(this::rebuildList);
            }
            final int done = okCount;
            toast("全部刷新完成：" + done + "/" + accounts.size() + " 成功");
        });
    }

    // ===== 添加账号：三种方式 =====
    private void showAddAccountDialog() {
        String[] items = {"粘贴 refresh token", "导入桌面端导出 JSON 文件", "导入 agy 的 antigravity-oauth-token 文件"};
        new AlertDialog.Builder(this)
                .setTitle("添加账号")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showPasteTokenDialog();
                    else if (which == 1) openFilePicker(REQ_IMPORT_DESKTOP);
                    else openFilePicker(REQ_IMPORT_TOKEN_FILE);
                })
                .show();
    }

    private void showPasteTokenDialog() {
        final EditText input = new EditText(this);
        input.setHint("粘贴 refresh token");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("粘贴 refresh token")
                .setView(input)
                .setPositiveButton("添加", (d, w) -> {
                    String token = input.getText().toString().trim();
                    if (token.isEmpty()) { toast("token 是空的"); return; }
                    addAccountByRefreshToken(token, "", "", "", "");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用 refresh token 建号：换 access token -> 拉 userinfo 补邮箱 -> 入库。已存在同邮箱则更新 token。 */
    private void addAccountByRefreshToken(final String refreshToken, final String presetEmail,
                                          final String presetName, final String presetProject,
                                          final String presetLabel) {
        toast("正在验证 token…");
        executor.execute(() -> {
            Account a = new Account();
            a.id = "acc_" + System.currentTimeMillis();
            a.createdAt = System.currentTimeMillis();
            a.refreshToken = refreshToken;
            a.email = presetEmail;
            a.name = presetName;
            a.projectId = presetProject;
            a.customLabel = presetLabel;
            try {
                refreshAccessToken(a);
                try { fetchUserInfo(a); } catch (Exception ignored) { }
            } catch (Exception e) {
                toast("添加失败：" + e.getMessage());
                return;
            }
            if (a.email.isEmpty() && presetEmail.isEmpty()) {
                toast("添加失败：拿不到邮箱，token 可能无效");
                return;
            }
            synchronized (accounts) {
                Account existing = findByEmail(a.email);
                if (existing != null) {
                    existing.refreshToken = a.refreshToken;
                    existing.accessToken = a.accessToken;
                    existing.expiryTs = a.expiryTs;
                    if (!a.name.isEmpty()) existing.name = a.name;
                    if (!a.projectId.isEmpty()) existing.projectId = a.projectId;
                    if (!a.customLabel.isEmpty()) existing.customLabel = a.customLabel;
                } else {
                    accounts.add(a);
                }
            }
            saveAccounts();
            runOnUiThread(this::rebuildList);
            toast("已添加 " + a.displayName());
        });
    }

    private Account findByEmail(String email) {
        if (email == null || email.isEmpty()) return null;
        for (Account a : accounts) {
            if (email.equals(a.email)) return a;
        }
        return null;
    }

    private void openFilePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            toast("打开文件选择器失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_DESKTOP) {
            readTextFile(uri, text -> importDesktopJson(text));
        } else if (requestCode == REQ_IMPORT_TOKEN_FILE) {
            readTextFile(uri, text -> importTokenFile(text));
        } else if (requestCode == REQ_EXPORT_ACCOUNTS) {
            writeTextFile(uri, buildExportJson());
            toast("已导出全部账号");
        } else if (requestCode == REQ_SAVE_TOKEN_FILE) {
            writeTextFile(uri, pendingTokenContent);
            toast("已保存 antigravity-oauth-token 文件");
        }
    }

    interface TextCallback { void onText(String text); }

    private void readTextFile(final Uri uri, final TextCallback cb) {
        executor.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("读不到文件");
                final String text = readAll(in);
                runOnUiThread(() -> cb.onText(text));
            } catch (Exception e) {
                toast("读文件失败：" + e.getMessage());
            }
        });
    }

    private void writeTextFile(final Uri uri, final String text) {
        executor.execute(() -> {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new Exception("写不出文件");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Exception e) {
                toast("写文件失败：" + e.getMessage());
            }
        });
    }

    /** 导入桌面端导出格式：{"schema_version":1,"accounts":[{email,refresh_token,name,project_id,custom_label}]} */
    private void importDesktopJson(String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("accounts");
            if (arr == null) { toast("导入失败：没有 accounts 数组，不是桌面端导出文件"); return; }
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String rt = o.optString("refresh_token", "");
                if (rt.isEmpty()) continue;
                addAccountByRefreshToken(rt,
                        o.optString("email", ""),
                        o.optString("name", ""),
                        o.optString("project_id", ""),
                        o.optString("custom_label", ""));
                count++;
            }
            toast(count > 0 ? "已提交导入 " + count + " 个账号，逐个验证中" : "导入失败：文件里没有带 refresh_token 的账号");
        } catch (Exception e) {
            toast("导入失败：JSON 解析错误 " + e.getMessage());
        }
    }

    /** 导入 agy 的 antigravity-oauth-token 文件：{"token":{"refresh_token":...,"access_token":...,"expiry":...}, ...} */
    private void importTokenFile(String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject token = root.optJSONObject("token");
            if (token == null) { toast("导入失败：没有 token 字段，不是 antigravity-oauth-token 文件"); return; }
            String rt = token.optString("refresh_token", "");
            if (rt.isEmpty()) { toast("导入失败：文件里没有 refresh_token"); return; }
            addAccountByRefreshToken(rt, "", "", "", "");
        } catch (Exception e) {
            toast("导入失败：JSON 解析错误 " + e.getMessage());
        }
    }

    // ===== 导出（桌面端兼容格式） =====
    private String buildExportJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", 1);
            JSONArray arr = new JSONArray();
            for (Account a : accounts) {
                JSONObject o = new JSONObject();
                o.put("email", a.email);
                o.put("refresh_token", a.refreshToken);
                if (!a.name.isEmpty()) o.put("name", a.name);
                if (!a.projectId.isEmpty()) o.put("project_id", a.projectId);
                if (!a.customLabel.isEmpty()) o.put("custom_label", a.customLabel);
                arr.put(o);
            }
            root.put("accounts", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"schema_version\":1,\"accounts\":[]}";
        }
    }

    private void startExport() {
        if (accounts.isEmpty()) { toast("还没有账号可导出"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "agy-switch-accounts.json");
        try {
            startActivityForResult(intent, REQ_EXPORT_ACCOUNTS);
        } catch (Exception e) {
            toast("打开文件选择器失败：" + e.getMessage());
        }
    }

    // ===== 编辑备注 / 删除 =====
    private void showEditLabelDialog(final Account a) {
        final EditText input = new EditText(this);
        input.setText(a.customLabel);
        input.setHint("备注（可空）");
        new AlertDialog.Builder(this)
                .setTitle("编辑备注 - " + a.email)
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    a.customLabel = input.getText().toString().trim();
                    saveAccounts();
                    rebuildList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Account a) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定删除 " + a.displayName() + " 吗？本地保存的 token 会一并删除。")
                .setPositiveButton("删除", (d, w) -> {
                    accounts.remove(a);
                    if (a.id.equals(currentAccountId)) currentAccountId = "";
                    saveAccounts();
                    rebuildList();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 切换当前账号 =====

    /** 生成 antigravity-oauth-token 文件内容（agy CLI 凭据格式） */
    private String buildTokenFileContent(Account a) throws Exception {
        JSONObject token = new JSONObject();
        token.put("access_token", a.accessToken);
        token.put("token_type", "Bearer");
        token.put("refresh_token", a.refreshToken);
        token.put("expiry", formatExpiry(a.expiryTs));
        JSONObject root = new JSONObject();
        root.put("token", token);
        root.put("auth_method", "consumer");
        if (a.idToken != null && !a.idToken.isEmpty()) {
            root.put("id_token", a.idToken);
        }
        return root.toString();
    }

    /** RFC3339，6 位小数 + Z，如 2026-01-01T00:00:00.000000Z */
    private String formatExpiry(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ts)) + "000Z";
    }

    private void switchToAccount(final Account a) {
        toast("正在准备切换到 " + a.displayName() + " …");
        executor.execute(() -> {
            // access token 过期或快过期时先刷新，保证写出的文件可用
            if (a.accessToken.isEmpty() || a.expiryTs < System.currentTimeMillis() + 60_000L) {
                try {
                    refreshAccessToken(a);
                    saveAccounts();
                } catch (Exception e) {
                    toast("切换失败：刷新 token 出错 " + e.getMessage());
                    return;
                }
            }
            final String content;
            try {
                content = buildTokenFileContent(a);
            } catch (Exception e) {
                toast("切换失败：生成凭据内容出错 " + e.getMessage());
                return;
            }
            boolean root = hasRoot();
            if (root) {
                boolean ok = writeTokenViaRoot(content);
                if (ok) {
                    markCurrent(a);
                    toast("已切换到 " + a.displayName()
                            + "，凭据已写入 Termux 的 antigravity-oauth-token。在 Termux 里重开 agy 才生效。");
                } else {
                    toast("root 写入失败，改用导出方式");
                    runOnUiThread(() -> showTokenContentDialog(a, content));
                }
            } else {
                markCurrent(a);
                runOnUiThread(() -> showTokenContentDialog(a, content));
            }
        });
    }

    private void markCurrent(Account a) {
        currentAccountId = a.id;
        a.lastUsed = System.currentTimeMillis();
        saveAccounts();
        runOnUiThread(this::rebuildList);
    }

    /** 检测 root：su -c id 输出含 uid=0 */
    private boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 用 root 把凭据写进 Termux 的 agy CLI 凭据路径 */
    private boolean writeTokenViaRoot(String content) {
        try {
            File tmp = new File(getFilesDir(), "pending-antigravity-oauth-token");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            String cmd = "mkdir -p '" + TERMUX_TOKEN_DIR + "'"
                    + " && cp '" + tmp.getAbsolutePath() + "' '" + TERMUX_TOKEN_PATH + "'"
                    + " && chmod 600 '" + TERMUX_TOKEN_PATH + "'";
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            readAll(p.getInputStream());
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 无 root 时：预览凭据内容，提供复制到剪贴板 / 保存到文件 */
    private void showTokenContentDialog(final Account a, final String content) {
        pendingTokenContent = content;
        final EditText view = new EditText(this);
        view.setText(content);
        view.setTextSize(11);
        view.setMinLines(8);
        view.setFocusable(false);
        ScrollView sv = new ScrollView(this);
        sv.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("已设为当前：" + a.displayName())
                .setMessage("本机没有 root，无法直接写入 Termux 目录。下面是 antigravity-oauth-token 的内容，"
                        + "复制或保存成文件后，放到 Termux 的 ~/.gemini/antigravity-cli/antigravity-oauth-token 即可。")
                .setView(sv)
                .setPositiveButton("复制到剪贴板", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("antigravity-oauth-token", content));
                        toast("已复制到剪贴板");
                    }
                })
                .setNeutralButton("保存到文件", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "antigravity-oauth-token");
                    try {
                        startActivityForResult(intent, REQ_SAVE_TOKEN_FILE);
                    } catch (Exception e) {
                        toast("打开文件选择器失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
