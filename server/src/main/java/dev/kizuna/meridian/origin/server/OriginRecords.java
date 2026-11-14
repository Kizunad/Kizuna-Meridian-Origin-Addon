package dev.kizuna.meridian.origin.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 玩家的出身记录存在他唯一身体的 addon_progress 里，不另建玩家账本；这里只做编解码和记录上的规则。 */
public final class OriginRecords {
    public static final String PROGRESS_KEY = "meridian_origin:origin";
    public static final int SCHEMA_VERSION = 1;
    private static final char GRANT_SEPARATOR = '#';

    private OriginRecords() {
        // 不在进程里另存一份。
    }

    public static OriginRecord read(BodySnapshot body) {
        // 还没抽过时进度里没有这一项，当成空记录；有但解不开、版本不对、内容不合法都是存档损坏，必须报错。
        Any stored = body.getAddonProgressMap().get(PROGRESS_KEY);
        if (stored == null) {
            return OriginRecord.newBuilder().setSchemaVersion(SCHEMA_VERSION).build();
        }
        OriginRecord record;
        try {
            record = stored.unpack(OriginRecord.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(OriginErrors.RECORD_CORRUPT, invalid);
        }
        if (record.getSchemaVersion() != SCHEMA_VERSION) {
            throw OriginErrors.invalid(OriginErrors.RECORD_VERSION, String.valueOf(record.getSchemaVersion()));
        }
        validate(record);
        return record;
    }

    public static void write(BodyActivity.Builder activity, OriginRecord record) {
        // 整份覆盖写入；写之前同样核对，坏记录不落盘。
        validate(record);
        activity.putProgress(PROGRESS_KEY, Any.pack(record));
    }

    public static OriginRecord offered(OriginRecord record, List<String> offered) {
        // 记下抽到的候选。
        return record.toBuilder().clearOffered().addAllOffered(offered).build();
    }

    public static Optional<OriginRecord> chosen(OriginRecord record, String origin, long nowMillis) {
        // 玩家自己选：只能选一次，只能选抽到的那几个。不合规矩时为空，记录不变。
        if (!record.getChosen().isEmpty() || !record.getOfferedList().contains(origin)) {
            return Optional.empty();
        }
        return Optional.of(assigned(record, origin, nowMillis));
    }

    public static OriginRecord assigned(OriginRecord record, String origin, long nowMillis) {
        // 直接定下出身，不看候选；调试指令用。已发过的一次性天赋照旧记着，新出身的另算。
        return record.toBuilder().setChosen(OriginIds.require(origin)).setChosenAtMillis(nowMillis).build();
    }

    public static OriginRecord redrawn(OriginRecord record) {
        // 调试用：清掉候选和选定，轮次加一，下次抽出另一组；已发过的一次性天赋不收回，也不重发。
        return record.toBuilder().clearOffered().clearChosen().clearChosenAtMillis()
                .setDrawRound(record.getDrawRound() + 1).build();
    }

    public static String grantKey(String origin, int index) {
        // 一次性天赋按「出身 + 它在 talents 里的下标」记账。
        return origin + GRANT_SEPARATOR + index;
    }

    public static OriginRecord granted(OriginRecord record, String grantKey) {
        // 发成一条记一条；已经记过的不重复记。
        if (record.getGrantedList().contains(grantKey)) {
            return record;
        }
        return record.toBuilder().addGranted(grantKey).build();
    }

    private static void validate(OriginRecord record) {
        // ID 都带命名空间，候选和已发记录不重复，轮次不为负。
        record.getOfferedList().forEach(OriginIds::require);
        if (!record.getChosen().isEmpty()) {
            OriginIds.require(record.getChosen());
        }
        if (new HashSet<>(record.getOfferedList()).size() != record.getOfferedCount()
                || new HashSet<>(record.getGrantedList()).size() != record.getGrantedCount()
                || record.getDrawRound() < 0 || record.getChosenAtMillis() < 0) {
            throw OriginErrors.invalid(OriginErrors.RECORD_CONTENT, record.toString());
        }
        for (String grant : record.getGrantedList()) {
            int separator = grant.lastIndexOf(GRANT_SEPARATOR);
            if (separator <= 0 || !OriginIds.valid(grant.substring(0, separator))
                    || !grant.substring(separator + 1).matches("\\d+")) {
                throw OriginErrors.invalid(OriginErrors.RECORD_CONTENT, grant);
            }
        }
    }
}
