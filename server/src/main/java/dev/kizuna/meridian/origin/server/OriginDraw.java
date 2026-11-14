package dev.kizuna.meridian.origin.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/** 抽候选出身：按权重不放回地抽，同一组至多出一个；同一个种子总抽出同一组。 */
public final class OriginDraw {
    private OriginDraw() {
        // 只有纯函数。
    }

    /** 一个能参与抽选的出身：ID、权重，以及可省的互斥组。 */
    public record Candidate(String id, int weight, Optional<String> group) {
        public Candidate {
            // 权重为零的出身不该出现在候选表里，直接拒绝。
            OriginIds.require(id);
            if (weight <= 0) {
                throw OriginErrors.invalid(OriginErrors.CANDIDATE_WEIGHT, id + " " + weight);
            }
        }
    }

    public static long seed(long worldSeed, UUID player, int round) {
        // 同一个世界里同一个玩家每一轮的种子固定，退出重进刷不出另一组；轮次只有调试指令会动。
        long mixed = worldSeed ^ player.getMostSignificantBits() * 0x9E3779B97F4A7C15L
                ^ Long.rotateLeft(player.getLeastSignificantBits(), 32) ^ round * 0xC2B2AE3D27D4EB4FL;
        return new SplittableRandom(mixed).nextLong();
    }

    public static List<String> draw(Collection<Candidate> candidates, int count, long seed) {
        // 从头抽一组。
        return refill(List.of(), candidates, count, seed);
    }

    public static List<String> refill(List<String> offered, Collection<Candidate> candidates, int count, long seed) {
        // 已经抽到、现在还能抽的留着不动；缺的按权重补到 count 个，能抽的不够就有几个给几个。
        List<Candidate> pool = sorted(candidates);
        var picked = new ArrayList<Candidate>();
        for (String id : offered) {
            pool.stream().filter(candidate -> candidate.id().equals(id)).findFirst().ifPresent(picked::add);
        }
        var random = new SplittableRandom(seed);
        while (picked.size() < count) {
            List<Candidate> open = open(pool, picked);
            if (open.isEmpty()) {
                break;
            }
            picked.add(weighted(open, random));
        }
        return picked.stream().map(Candidate::id).toList();
    }

    private static List<Candidate> sorted(Collection<Candidate> candidates) {
        // 按 ID 排序，结果不随数据包的读取顺序变；ID 重复说明候选表拼错了。
        List<Candidate> pool = candidates.stream().sorted(Comparator.comparing(Candidate::id)).toList();
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : pool) {
            if (!seen.add(candidate.id())) {
                throw OriginErrors.invalid(OriginErrors.CANDIDATE_DUPLICATE, candidate.id());
            }
        }
        return pool;
    }

    private static List<Candidate> open(List<Candidate> pool, List<Candidate> picked) {
        // 还没抽到、组也没被占的出身。
        Set<String> groups = new HashSet<>();
        picked.forEach(candidate -> candidate.group().ifPresent(groups::add));
        return pool.stream()
                .filter(candidate -> !picked.contains(candidate))
                .filter(candidate -> candidate.group().filter(groups::contains).isEmpty())
                .toList();
    }

    private static Candidate weighted(List<Candidate> open, SplittableRandom random) {
        // 在权重总和里取一个点，落在谁的区间就是谁。
        long total = open.stream().mapToLong(Candidate::weight).sum();
        long point = random.nextLong(total);
        for (Candidate candidate : open) {
            point -= candidate.weight();
            if (point < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("权重区间没有覆盖抽到的点");
    }
}
