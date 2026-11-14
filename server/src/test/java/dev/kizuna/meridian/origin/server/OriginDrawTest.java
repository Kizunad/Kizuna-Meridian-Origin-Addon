package dev.kizuna.meridian.origin.server;

import dev.kizuna.meridian.origin.server.OriginDraw.Candidate;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 抽候选出身：同种子同结果、按权重、同组至多一个、缺了只补缺的。 */
public final class OriginDrawTest {
    private static final long WORLD_SEED = 20261115L;
    private static final UUID PLAYER = UUID.fromString("0e3c7a52-5b1e-4c37-9d0a-6b2f4f8f1a10");
    private static final int OFFER = 3;
    private static final int ROUNDS = 4000;
    private static final double RATE_TOLERANCE = 0.03;
    private static final List<Candidate> PLAIN = List.of(plain("t:farmer", 10), plain("t:orphan", 10),
            plain("t:apprentice", 10), plain("t:convict", 10), plain("t:merchant", 10));

    @Test
    public void sameSeedDrawsTheSameSetWhateverTheInputOrder() {
        // 同一个世界里同一个玩家总抽出同一组，和数据包读出来的先后无关；不重复，正好三个。
        long seed = OriginDraw.seed(WORLD_SEED, PLAYER, 0);
        List<String> first = OriginDraw.draw(PLAIN, OFFER, seed);
        assertEquals(OFFER, new HashSet<>(first).size());
        assertEquals(first, OriginDraw.draw(PLAIN.reversed(), OFFER, seed));
        assertEquals(seed, OriginDraw.seed(WORLD_SEED, PLAYER, 0));
    }

    @Test
    public void anotherPlayerWorldOrRoundGetsAnotherSeed() {
        // 换玩家、换世界、调试重抽的轮次，种子都不同。
        long seed = OriginDraw.seed(WORLD_SEED, PLAYER, 0);
        assertNotEquals(seed, OriginDraw.seed(WORLD_SEED, UUID.nameUUIDFromBytes(new byte[]{1}), 0));
        assertNotEquals(seed, OriginDraw.seed(WORLD_SEED + 1, PLAYER, 0));
        assertNotEquals(seed, OriginDraw.seed(WORLD_SEED, PLAYER, 1));
    }

    @Test
    public void heavierOriginsAreDrawnMoreOften() {
        // 只抽一个时，权重 3 比 1 的出身出现的次数接近三比一。
        List<Candidate> weighted = List.of(plain("t:common", 3), plain("t:rare", 1));
        int common = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (OriginDraw.draw(weighted, 1, round).equals(List.of("t:common"))) {
                common++;
            }
        }
        assertEquals(0.75, (double) common / ROUNDS, RATE_TOLERANCE);
    }

    @Test
    public void oneGroupShowsUpAtMostOncePerDraw() {
        // 五行灵体的五个变体同属一组：一次抽选里至多出一个，哪怕候选里它们占多数。
        List<Candidate> candidates = List.of(grouped("t:spirit_metal"), grouped("t:spirit_wood"),
                grouped("t:spirit_water"), grouped("t:spirit_fire"), plain("t:farmer", 1), plain("t:orphan", 1));
        Map<String, Integer> seen = new HashMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            List<String> drawn = OriginDraw.draw(candidates, OFFER, round);
            assertEquals(OFFER, drawn.size());
            assertTrue(drawn.toString(), drawn.stream().filter(id -> id.startsWith("t:spirit_")).count() <= 1);
            drawn.forEach(id -> seen.merge(id, 1, Integer::sum));
        }
        assertEquals(candidates.size(), seen.size());
    }

    @Test
    public void fewerOriginsThanAskedGivesWhatThereIs() {
        // 能抽的不够三个时有几个给几个；一个都没有就是空。
        assertEquals(2, OriginDraw.draw(PLAIN.subList(0, 2), OFFER, 1).size());
        assertEquals(List.of("t:spirit_metal"), OriginDraw.draw(List.of(grouped("t:spirit_metal")), OFFER, 1));
        assertEquals(1, OriginDraw.draw(List.of(grouped("t:spirit_metal"), grouped("t:spirit_wood")), OFFER, 1).size());
        assertTrue(OriginDraw.draw(List.of(), OFFER, 1).isEmpty());
    }

    @Test
    public void refillKeepsWhatStillExistsAndOnlyFillsTheGap() {
        // 抽到的三个里有一个被数据包删了：另外两个原样留着，只补一个；一个都没少时不动。
        long seed = OriginDraw.seed(WORLD_SEED, PLAYER, 0);
        List<String> offered = OriginDraw.draw(PLAIN, OFFER, seed);
        assertEquals(offered, OriginDraw.refill(offered, PLAIN, OFFER, seed));
        String removed = offered.get(1);
        List<Candidate> remaining = PLAIN.stream().filter(candidate -> !candidate.id().equals(removed)).toList();
        List<String> refilled = OriginDraw.refill(offered, remaining, OFFER, seed);
        assertEquals(List.of(offered.get(0), offered.get(2)), refilled.subList(0, 2));
        assertEquals(OFFER, new HashSet<>(refilled).size());
        assertTrue(!refilled.contains(removed));
    }

    @Test
    public void brokenCandidatesAreRejected() {
        // 权重不是正数、ID 不带命名空间、ID 重复都拒绝。
        assertThrows(IllegalArgumentException.class, () -> plain("t:zero", 0));
        assertThrows(IllegalArgumentException.class, () -> plain("farmer", 1));
        assertThrows(IllegalArgumentException.class,
                () -> OriginDraw.draw(List.of(plain("t:farmer", 1), plain("t:farmer", 2)), OFFER, 1));
    }

    private static Candidate plain(String id, int weight) {
        return new Candidate(id, weight, Optional.empty());
    }

    private static Candidate grouped(String id) {
        return new Candidate(id, 5, Optional.of("spirit"));
    }
}
