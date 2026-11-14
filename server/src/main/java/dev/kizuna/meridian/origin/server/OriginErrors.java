package dev.kizuna.meridian.origin.server;

/** 出身纯规则的固定错误文本；调用方只转述，不拼接内部状态。 */
public final class OriginErrors {
    public static final String SETTINGS_RESOURCE = "缺少出身默认配置资源";
    public static final String SETTINGS_READ = "无法读取出身配置";
    public static final String SETTINGS_FORMAT = "出身配置无法解析";
    public static final String SETTINGS_KEY = "出身配置里有不认识的键";
    public static final String SETTINGS_SELECTION = "selection 必须是布尔值";
    public static final String SETTINGS_OFFER_COUNT = "offer_count 必须是 1～8 的整数";
    public static final String SETTINGS_DISABLED = "disabled 必须是出身 ID 的数组";
    public static final String ORIGIN_ID = "出身 ID 必须是带命名空间的 ID";
    public static final String CANDIDATE_WEIGHT = "出身的抽选权重必须是正整数";
    public static final String CANDIDATE_DUPLICATE = "出身 ID 重复";
    public static final String RECORD_CORRUPT = "出身记录损坏";
    public static final String RECORD_VERSION = "不支持的出身记录版本";
    public static final String RECORD_CONTENT = "出身记录内容不合法";

    private OriginErrors() {
        // 只保存常量。
    }

    static IllegalArgumentException invalid(String problem, String detail) {
        return new IllegalArgumentException(problem + ": " + detail);
    }
}
