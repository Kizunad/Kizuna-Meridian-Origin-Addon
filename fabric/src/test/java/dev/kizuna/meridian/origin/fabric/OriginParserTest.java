package dev.kizuna.meridian.origin.fabric;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 出身 JSON 的解析：类型要登记过，参数交给登记方核对，写错的地方报得出是哪一项。 */
public final class OriginParserTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("t", "orphan");
    private static final OriginTalentType<Level> LEVEL = OriginTalentType.standing(
            Identifier.fromNamespaceAndPath("t", "level"), Level.CODEC);
    private static final OriginThreatType<Level> FEUD = new OriginThreatType<>(
            Identifier.fromNamespaceAndPath("t", "feud"), Level.CODEC);
    private static final String FEUD_ENTRY = "{ \"type\": \"t:feud\", \"params\": { \"level\": 3 } }";
    private static final String VALID = """
            { "weight": 7, "icon": "minecraft:iron_sword", "group": "family",
              "talents": [ { "type": "t:level", "params": { "level": 2 } },
                           { "type": "t:level", "params": { "level": 5 } } ],
              "threats": [ { "type": "t:feud", "params": { "level": 3 } } ] }""";

    @Test
    public void validOriginKeepsItsTalentsAndThreatsInOrder() {
        // 权重、图标、组照写；同一类型写两条就得到两份参数，按 JSON 里的先后。
        Origin origin = parser().parse(ID, JsonParser.parseString(VALID));
        assertEquals(new Origin(ID, 7, Identifier.parse("minecraft:iron_sword"), Optional.of("family"),
                List.of(new Origin.Talent<>(LEVEL, new Level(2)), new Origin.Talent<>(LEVEL, new Level(5))),
                List.of(new Origin.Threat<>(FEUD, new Level(3)))), origin);
        assertEquals(List.of(new Level(2), new Level(5)), origin.talents(LEVEL));
        assertEquals(List.of(new Level(3)), origin.threats(FEUD));
        Origin bare = parser().parse(ID, JsonParser.parseString(
                "{ \"weight\": 1, \"icon\": \"minecraft:wheat\", \"talents\": [], \"threats\": [] }"));
        assertTrue(bare.group().isEmpty() && bare.talents().isEmpty() && bare.threats().isEmpty());
    }

    @Test
    public void brokenOriginsAreRejectedNamingTheBrokenPart() {
        // 类型没登记、参数越界、多了不认识的键、权重不对、图标不带命名空间、缺数组，都拒绝；报错里写得出是哪一项。
        assertRejected(VALID.replace("t:level\", \"params\": { \"level\": 5", "t:speed\", \"params\": { \"level\": 5"),
                "talents[1]", "t:speed");
        assertRejected(VALID.replace("\"level\": 3", "\"level\": 99"), "threats[0]", "参数");
        assertRejected(VALID.replace("\"weight\": 7", "\"weight\": 7, \"wieght\": 1"), "wieght");
        assertRejected(VALID.replace("\"weight\": 7", "\"weight\": 0"), "weight");
        assertRejected(VALID.replace("\"weight\": 7", "\"weight\": 1.5"), "weight");
        assertRejected(VALID.replace("minecraft:iron_sword", "iron_sword"), "icon");
        assertRejected(VALID.replace(FEUD_ENTRY, "{ \"type\": \"t:feud\" } ], \"x\": [ 1"), "x");
        assertRejected("{ \"weight\": 1, \"icon\": \"minecraft:wheat\", \"talents\": [] }", "threats");
        assertRejected(VALID.replace(FEUD_ENTRY, "{ \"note\": 1, \"type\": \"t:feud\" }"), "threats[0]", "note");
        assertRejected("[]", "出身");
    }

    private static void assertRejected(String json, String... mentions) {
        var failure = assertThrows(json, IllegalArgumentException.class,
                () -> parser().parse(ID, JsonParser.parseString(json)));
        for (String mention : mentions) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(mention));
        }
    }

    private static OriginParser parser() {
        // 登记表里只有一种天赋、一种威胁。
        var types = new OriginTypes();
        types.talent(LEVEL);
        types.threat(FEUD);
        return new OriginParser(types);
    }

    /** 测试参数：1～9 的一个数。 */
    private record Level(int level) {
        static final Codec<Level> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, 9).fieldOf("level").forGetter(Level::level)).apply(instance, Level::new));
    }
}
