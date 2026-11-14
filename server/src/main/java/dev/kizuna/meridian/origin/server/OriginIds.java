package dev.kizuna.meridian.origin.server;

import java.util.regex.Pattern;

/** 出身 ID 的写法：和 Minecraft 的资源 ID 相同，必须带命名空间。 */
public final class OriginIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private OriginIds() {
        // 只做格式判断。
    }

    public static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static String require(String id) {
        // 配置、记录和候选表里的 ID 都过这一处。
        if (!valid(id)) {
            throw OriginErrors.invalid(OriginErrors.ORIGIN_ID, String.valueOf(id));
        }
        return id;
    }
}
