package dev.kizuna.meridian.origin.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 出身记录：读写、只能选一次、只能选抽到的、一次性天赋记账。 */
public final class OriginRecordsTest {
    private static final List<String> OFFERED = List.of("t:farmer", "t:orphan", "t:apprentice");
    private static final long NOW = 123_456L;

    @Test
    public void recordSurvivesTheBodyProgressRoundTrip() {
        // 写进身体活动、再从身体读回来是同一份；身体上没有这一项时是空记录。
        OriginRecord empty = OriginRecords.read(BodySnapshot.getDefaultInstance());
        assertTrue(empty.getOfferedList().isEmpty());
        assertTrue(empty.getChosen().isEmpty());
        OriginRecord offered = OriginRecords.offered(empty, OFFERED);
        OriginRecord chosen = OriginRecords.chosen(offered, "t:orphan", NOW).orElseThrow();
        OriginRecord granted = OriginRecords.granted(chosen, OriginRecords.grantKey("t:orphan", 0));
        assertEquals(granted, OriginRecords.read(stored(granted)));
        assertEquals(OFFERED, granted.getOfferedList());
        assertEquals(NOW, granted.getChosenAtMillis());
    }

    @Test
    public void playerChoosesOnceAndOnlyFromWhatWasOffered() {
        // 没抽到的不能选；选过以后再选任何一个都不行，记录不变。
        OriginRecord offered = OriginRecords.offered(OriginRecords.read(BodySnapshot.getDefaultInstance()), OFFERED);
        assertEquals(Optional.empty(), OriginRecords.chosen(offered, "t:convict", NOW));
        OriginRecord chosen = OriginRecords.chosen(offered, "t:farmer", NOW).orElseThrow();
        assertEquals("t:farmer", chosen.getChosen());
        assertEquals(Optional.empty(), OriginRecords.chosen(chosen, "t:orphan", NOW + 1));
        assertEquals(Optional.empty(), OriginRecords.chosen(chosen, "t:farmer", NOW + 1));
    }

    @Test
    public void oneShotTalentsAreBookedOnce() {
        // 发成一条记一条；同一条再记不变。调试重抽清掉候选和选定、轮次加一，已发过的不清。
        OriginRecord offered = OriginRecords.offered(OriginRecords.read(BodySnapshot.getDefaultInstance()), OFFERED);
        OriginRecord chosen = OriginRecords.chosen(offered, "t:orphan", NOW).orElseThrow();
        String key = OriginRecords.grantKey("t:orphan", 1);
        OriginRecord granted = OriginRecords.granted(chosen, key);
        assertEquals(List.of(key), granted.getGrantedList());
        assertEquals(granted, OriginRecords.granted(granted, key));
        OriginRecord redrawn = OriginRecords.redrawn(granted);
        assertTrue(redrawn.getOfferedList().isEmpty());
        assertTrue(redrawn.getChosen().isEmpty());
        assertEquals(1, redrawn.getDrawRound());
        assertEquals(List.of(key), redrawn.getGrantedList());
    }

    @Test
    public void debugAssignmentIgnoresTheOffer() {
        // 调试指令直接定出身：不看候选，也能盖掉已选的。
        OriginRecord offered = OriginRecords.offered(OriginRecords.read(BodySnapshot.getDefaultInstance()), OFFERED);
        OriginRecord assigned = OriginRecords.assigned(offered, "t:convict", NOW);
        assertEquals("t:convict", assigned.getChosen());
        assertEquals("t:farmer", OriginRecords.assigned(assigned, "t:farmer", NOW).getChosen());
        assertThrows(IllegalArgumentException.class, () -> OriginRecords.assigned(offered, "convict", NOW));
    }

    @Test
    public void damagedRecordsAreRejectedOnReadAndWrite() {
        // 版本不对、不是这种消息、ID 不带命名空间、候选重复、已发记录写错，读和写都报错。
        OriginRecord valid = OriginRecord.newBuilder().setSchemaVersion(OriginRecords.SCHEMA_VERSION).build();
        assertThrows(IllegalArgumentException.class,
                () -> OriginRecords.read(stored(valid.toBuilder().setSchemaVersion(2).build())));
        assertThrows(IllegalArgumentException.class, () -> OriginRecords.read(BodySnapshot.newBuilder()
                .putAddonProgress(OriginRecords.PROGRESS_KEY, Any.pack(BodySnapshot.getDefaultInstance())).build()));
        for (OriginRecord broken : List.of(valid.toBuilder().addOffered("farmer").build(),
                valid.toBuilder().addOffered("t:farmer").addOffered("t:farmer").build(),
                valid.toBuilder().setChosen("Farmer").build(),
                valid.toBuilder().addGranted("t:farmer").build(),
                valid.toBuilder().addGranted("t:farmer#x").build())) {
            assertThrows(IllegalArgumentException.class, () -> OriginRecords.read(stored(broken)));
            assertThrows(IllegalArgumentException.class,
                    () -> OriginRecords.write(BodyActivity.newBuilder(), broken));
        }
        assertFalse(valid.getOfferedList().contains("t:farmer"));
    }

    private static BodySnapshot stored(OriginRecord record) {
        // 绕过写入时的核对，直接把一份记录放到身体进度里。
        return BodySnapshot.newBuilder().putAddonProgress(OriginRecords.PROGRESS_KEY, Any.pack(record)).build();
    }
}
