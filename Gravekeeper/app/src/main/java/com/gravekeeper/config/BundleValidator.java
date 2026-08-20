package com.gravekeeper.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.gravekeeper.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Installs APK resources into a verified, app-private current/previous pair.
 * User configuration remains in SharedPreferences and is never copied into a slot.
 */
public final class BundleValidator {
    private static final String MANIFEST_ASSET = "config/guard_bundle_manifest.json";
    private static final String MANIFEST_FILE = "guard_bundle_manifest.json";
    private static final String STORE_DIRECTORY = "guard_resource_bundles";
    private static final String CURRENT = "current";
    private static final String PREVIOUS = "previous";
    private static final String STAGING = "staging";
    private static final String STATE_PREFERENCES = "guard_bundle_state";
    private static final String KEY_REJECTED_CURRENT = "rejected_current_manifest_sha256";
    private static final String KEY_REJECTED_APP_VERSION = "rejected_current_app_version";
    private static final String KEY_ACTIVE_SLOT = "active_slot";
    private static final String KEY_FALLBACK = "fallback_active";
    private static final String KEY_REASON = "fallback_reason";

    private static final Set<String> REQUIRED_FILES = new HashSet<>(Arrays.asList(
            "models/gravekeeper_visual.tflite",
            "models/text_classifier_int8.bin",
            "models/fusion_model.json",
            "config/rule_schema.json",
            "config/guard_runtime_config.json"));

    private static ResourceBundle cached;

    private BundleValidator() {}

    public static final class ResourceBundle {
        private final File directory;
        public final String slot;
        public final int version;
        public final String candidateId;
        public final String modelsVersion;
        public final int rulesVersion;
        public final int runtimeConfigVersion;
        public final int rollbackCompatibleMinBundleVersion;
        public final String manifestSha256;
        public final boolean fallback;
        public final String fallbackReason;

        private ResourceBundle(File directory, String slot, JSONObject manifest,
                String fingerprint, boolean fallback, String fallbackReason)
                throws JSONException {
            this.directory = directory;
            this.slot = slot;
            version = manifest.getInt("version");
            candidateId = manifest.getString("candidate_id");
            modelsVersion = manifest.getString("models_version");
            rulesVersion = manifest.getInt("rules_version");
            runtimeConfigVersion = manifest.getInt("runtime_config_version");
            rollbackCompatibleMinBundleVersion = manifest.getInt(
                    "rollback_compatible_min_bundle_version");
            manifestSha256 = fingerprint;
            this.fallback = fallback;
            this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        public File file(String path) throws IOException {
            return checkedChild(directory, path);
        }

        public InputStream open(String path) throws IOException {
            return new FileInputStream(file(path));
        }
    }

    private static final class Slot {
        final File directory;
        final JSONObject manifest;
        final String fingerprint;

        Slot(File directory, JSONObject manifest, String fingerprint) {
            this.directory = directory;
            this.manifest = manifest;
            this.fingerprint = fingerprint;
        }
    }

    private static final class Bundled {
        final JSONObject manifest;
        final byte[] manifestBytes;
        final String fingerprint;

        Bundled(JSONObject manifest, byte[] manifestBytes, String fingerprint) {
            this.manifest = manifest;
            this.manifestBytes = manifestBytes;
            this.fingerprint = fingerprint;
        }
    }

    public static void validate(Context context) throws IOException {
        active(context);
    }

    public static synchronized ResourceBundle active(Context context) throws IOException {
        if (cached != null) return cached;
        Context app = context.getApplicationContext();
        File store = new File(app.getFilesDir(), STORE_DIRECTORY);
        ensureDirectory(store);
        File currentDirectory = new File(store, CURRENT);
        File previousDirectory = new File(store, PREVIOUS);
        Slot current = trySlot(currentDirectory);
        Slot previous = trySlot(previousDirectory);
        SharedPreferences state = state(app);
        String rejected = state.getInt(KEY_REJECTED_APP_VERSION, -1)
                == BuildConfig.VERSION_CODE
                ? state.getString(KEY_REJECTED_CURRENT, "") : "";

        Bundled bundled = null;
        IOException bundledError = null;
        try {
            bundled = readBundled(app);
        } catch (IOException error) {
            bundledError = error;
        }

        if (bundled != null && (current == null
                || !bundled.fingerprint.equals(current.fingerprint))) {
            try {
                installBundled(app, store, bundled, current != null);
                current = validateSlot(currentDirectory);
                previous = trySlot(previousDirectory);
                state.edit().remove(KEY_REJECTED_CURRENT)
                        .remove(KEY_REJECTED_APP_VERSION).apply();
                rejected = "";
            } catch (IOException installError) {
                bundledError = installError;
                current = trySlot(currentDirectory);
                previous = trySlot(previousDirectory);
            }
        }

        if (current != null && !current.fingerprint.equals(rejected)) {
            boolean fallback = bundledError != null;
            String reason = fallback ? "APK 内置资源无效，继续使用已验证资源："
                    + safeMessage(bundledError) : "";
            return setActive(app, current, CURRENT, fallback, reason);
        }
        if (previous != null && (current == null || rollbackCompatible(current, previous))) {
            String reason = current == null
                    ? "当前资源槽无效，已回退上一有效版本"
                    : "当前资源加载曾失败，已回退上一有效版本";
            if (bundledError != null) reason += "；" + safeMessage(bundledError);
            return setActive(app, previous, PREVIOUS, true, reason);
        }
        if (current != null) {
            // No rollback exists. Retry the only verified slot so first installs do not
            // become permanently rejected after a transient model initialization error.
            state.edit().remove(KEY_REJECTED_CURRENT)
                    .remove(KEY_REJECTED_APP_VERSION).apply();
            return setActive(app, current, CURRENT, false, "");
        }
        throw new IOException("没有可用的本地资源包", bundledError);
    }

    /** Marks a contract/model initialization failure and switches to previous. */
    public static synchronized ResourceBundle fallbackToPrevious(
            Context context, ResourceBundle failed, Throwable failure) throws IOException {
        if (failed == null || !CURRENT.equals(failed.slot)) return null;
        Context app = context.getApplicationContext();
        File store = new File(app.getFilesDir(), STORE_DIRECTORY);
        Slot previous = trySlot(new File(store, PREVIOUS));
        if (previous == null || previous.manifest.optInt("version", 0)
                < failed.rollbackCompatibleMinBundleVersion) return null;
        String reason = "当前资源契约或模型加载失败，已回退上一有效版本："
                + safeMessage(failure);
        state(app).edit()
                .putString(KEY_REJECTED_CURRENT, failed.manifestSha256)
                .putInt(KEY_REJECTED_APP_VERSION, BuildConfig.VERSION_CODE)
                .apply();
        cached = null;
        return setActive(app, previous, PREVIOUS, true, reason);
    }

    public static synchronized void clearProcessCache() {
        cached = null;
    }

    private static ResourceBundle setActive(Context context, Slot slot, String name,
            boolean fallback, String reason) throws IOException {
        try {
            ResourceBundle result = new ResourceBundle(
                    slot.directory, name, slot.manifest, slot.fingerprint, fallback, reason);
            state(context).edit()
                    .putString(KEY_ACTIVE_SLOT, name)
                    .putBoolean(KEY_FALLBACK, fallback)
                    .putString(KEY_REASON, reason == null ? "" : reason)
                    .apply();
            cached = result;
            return result;
        } catch (JSONException error) {
            throw new IOException("资源包元数据无效", error);
        }
    }

    private static Bundled readBundled(Context context) throws IOException {
        byte[] manifestBytes;
        try (InputStream input = context.getAssets().open(MANIFEST_ASSET)) {
            manifestBytes = readAll(input);
        }
        try {
            JSONObject manifest = new JSONObject(new String(
                    manifestBytes, StandardCharsets.UTF_8));
            validateManifest(manifest);
            validateAssetFiles(context, manifest);
            validateResourceContracts(manifest, path -> context.getAssets().open(path));
            return new Bundled(manifest, manifestBytes, sha256(manifestBytes));
        } catch (JSONException error) {
            throw new IOException("APK 资源包 manifest 无效", error);
        }
    }

    private static void installBundled(Context context, File store, Bundled bundled,
            boolean preserveCurrent) throws IOException {
        File staging = new File(store, STAGING);
        deleteTreeInside(store, staging);
        ensureDirectory(staging);
        JSONArray files;
        try {
            files = bundled.manifest.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) {
                String path = files.getJSONObject(index).getString("path");
                File destination = checkedChild(staging, path);
                ensureDirectory(destination.getParentFile());
                try (InputStream input = context.getAssets().open(path)) {
                    copyAndSync(input, destination);
                }
            }
            copyAndSync(new java.io.ByteArrayInputStream(bundled.manifestBytes),
                    new File(staging, MANIFEST_FILE));
        } catch (JSONException error) {
            deleteTreeInside(store, staging);
            throw new IOException("无法安装 APK 资源包", error);
        }
        validateSlot(staging);

        File current = new File(store, CURRENT);
        File previous = new File(store, PREVIOUS);
        if (preserveCurrent && current.isDirectory()) {
            deleteTreeInside(store, previous);
            if (!current.renameTo(previous)) {
                deleteTreeInside(store, staging);
                throw new IOException("无法保存上一有效资源版本");
            }
        } else {
            deleteTreeInside(store, current);
        }
        if (!staging.renameTo(current)) {
            if (preserveCurrent && previous.isDirectory() && !current.exists()) {
                previous.renameTo(current);
            }
            throw new IOException("无法启用新资源版本");
        }
    }

    private interface StreamSource {
        InputStream open(String path) throws IOException;
    }

    private static Slot trySlot(File directory) {
        try {
            return validateSlot(directory);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Slot validateSlot(File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("资源槽不存在");
        File manifestFile = new File(directory, MANIFEST_FILE);
        byte[] manifestBytes;
        try (InputStream input = new FileInputStream(manifestFile)) {
            manifestBytes = readAll(input);
        }
        try {
            JSONObject manifest = new JSONObject(new String(
                    manifestBytes, StandardCharsets.UTF_8));
            validateManifest(manifest);
            validateDirectoryFiles(directory, manifest);
            validateResourceContracts(manifest, path ->
                    new FileInputStream(checkedChild(directory, path)));
            return new Slot(directory, manifest, sha256(manifestBytes));
        } catch (JSONException error) {
            throw new IOException("资源槽 manifest 无效", error);
        }
    }

    private static void validateManifest(JSONObject manifest)
            throws JSONException, IOException {
        if (!"gravekeeper_bundle".equals(manifest.getString("format"))) {
            throw new IOException("不支持的资源包格式");
        }
        if (manifest.getInt("version") < 1) throw new IOException("资源版本无效");
        int minimumRollback = manifest.getInt("rollback_compatible_min_bundle_version");
        if (minimumRollback < 1 || minimumRollback > manifest.getInt("version")) {
            throw new IOException("资源回退兼容版本无效");
        }
        if (BuildConfig.VERSION_CODE < manifest.getInt("minimum_app_version_code")) {
            throw new IOException("资源包需要更新的 App 版本");
        }
        if (manifest.getInt("runtime_config_version") < 5
                || manifest.getInt("platform_config_version") < 5
                || manifest.getString("ocr_runtime_version").trim().isEmpty()) {
            throw new IOException("资源包运行配置契约过旧");
        }
        JSONObject contracts = manifest.getJSONObject("contracts");
        if (contracts.getInt("resource_layout_version") != 1
                || !"float32[1,3,416,192]->float32[1,1]".equals(
                        contracts.getString("visual"))
                || !"char-ngram-int8-262144x3-v1".equals(
                        contracts.getString("text"))
                || !"day43-fusion-18-feature-v1".equals(
                        contracts.getString("fusion"))) {
            throw new IOException("资源包契约不兼容");
        }
        JSONArray files = manifest.getJSONArray("files");
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < files.length(); index++) {
            JSONObject file = files.getJSONObject(index);
            String path = file.getString("path");
            validateRelativePath(path);
            if (!paths.add(path)) throw new IOException("资源路径重复：" + path);
            if (file.getLong("bytes") <= 0
                    || !file.getString("sha256").matches("[0-9a-fA-F]{64}")) {
                throw new IOException("资源摘要无效：" + path);
            }
        }
        if (!paths.containsAll(REQUIRED_FILES)) {
            throw new IOException("资源包缺少生产运行文件");
        }
    }

    private static void validateAssetFiles(Context context, JSONObject manifest)
            throws JSONException, IOException {
        JSONArray files = manifest.getJSONArray("files");
        for (int index = 0; index < files.length(); index++) {
            JSONObject item = files.getJSONObject(index);
            try (InputStream input = context.getAssets().open(item.getString("path"))) {
                validateStream(input, item.getLong("bytes"), item.getString("sha256"),
                        item.getString("path"));
            }
        }
    }

    private static boolean rollbackCompatible(Slot current, Slot previous) {
        return previous.manifest.optInt("version", 0) >= current.manifest.optInt(
                "rollback_compatible_min_bundle_version", Integer.MAX_VALUE);
    }

    private static void validateDirectoryFiles(File directory, JSONObject manifest)
            throws JSONException, IOException {
        JSONArray files = manifest.getJSONArray("files");
        for (int index = 0; index < files.length(); index++) {
            JSONObject item = files.getJSONObject(index);
            String path = item.getString("path");
            try (InputStream input = new FileInputStream(checkedChild(directory, path))) {
                validateStream(input, item.getLong("bytes"), item.getString("sha256"), path);
            }
        }
    }

    private static void validateResourceContracts(JSONObject manifest, StreamSource source)
            throws IOException, JSONException {
        JSONObject runtime = readJson(source.open("config/guard_runtime_config.json"));
        if (!"gravekeeper_runtime_config".equals(runtime.optString("format"))
                || runtime.getInt("version") != manifest.getInt("runtime_config_version")) {
            throw new IOException("默认运行配置契约不匹配");
        }
        JSONObject fusion = readJson(source.open("models/fusion_model.json"));
        JSONArray order = fusion.getJSONArray("feature_order");
        String[] expected = {
                "visual_score", "text_sales_score", "text_health_score",
                "text_elderly_score", "health_keyword_count",
                "sales_keyword_count", "elderly_keyword_count",
                "negative_context_count", "price_present",
                "shopping_cart_present", "order_prompt_present",
                "account_blacklist_hit", "account_whitelist_hit",
                "ocr_available", "collector_overlay", "black_occlusion",
                "loading_or_blank", "strong_positive_rule"};
        if (order.length() != expected.length) throw new IOException("融合特征数量不匹配");
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals(order.getString(index))) {
                throw new IOException("融合特征顺序不匹配");
            }
        }
        JSONObject rules = readJson(source.open("config/rule_schema.json"));
        if (!rules.has("keywords") || !rules.has("price_regex")) {
            throw new IOException("规则契约不匹配");
        }
        try (InputStream textModel = source.open("models/text_classifier_int8.bin")) {
            byte[] header = new byte[12];
            int read = 0;
            while (read < header.length) {
                int count = textModel.read(header, read, header.length - read);
                if (count < 0) break;
                read += count;
            }
            if (read != 12 || header[0] != 'H' || header[1] != 'T'
                    || header[2] != 'C' || header[3] != '1'
                    || littleEndianInt(header, 4) != 3
                    || littleEndianInt(header, 8) != 262144) {
                throw new IOException("文本模型契约不匹配");
            }
        }
    }

    private static JSONObject readJson(InputStream input) throws IOException {
        try (InputStream stream = input) {
            try {
                return new JSONObject(new String(readAll(stream), StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IOException("JSON 资源无效", error);
            }
        }
    }

    private static int littleEndianInt(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24);
    }

    private static void validateStream(InputStream input, long expectedBytes,
            String expectedSha256, String path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
            bytes += count;
        }
        if (bytes != expectedBytes || !hex(digest.digest()).equalsIgnoreCase(expectedSha256)) {
            throw new IOException("资源完整性校验失败：" + path);
        }
    }

    private static File checkedChild(File root, String relative) throws IOException {
        validateRelativePath(relative);
        File child = new File(root, relative);
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(rootPath + File.separator)) {
            throw new IOException("资源路径越界");
        }
        return child;
    }

    private static void validateRelativePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.startsWith("\\") || path.contains("\\")) {
            throw new IOException("资源路径无效");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("资源路径无效");
            }
        }
    }

    private static void copyAndSync(InputStream input, File destination) throws IOException {
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void deleteTreeInside(File store, File target) throws IOException {
        String storePath = store.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(storePath + File.separator)) {
            throw new IOException("拒绝删除资源目录之外的路径");
        }
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteTreeInside(store, child);
        }
        if (!target.delete()) throw new IOException("无法清理旧资源槽");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("资源目录无效");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建资源目录");
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws IOException {
        return hex(digest().digest(bytes));
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 不可用", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(
                Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "未知错误";
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
