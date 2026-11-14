# 给别的附属用的接口

出身附属只依赖 Framework。别的附属要实现天赋或威胁，就硬依赖本仓（`meridian_origin`），做两件事：登记类型，在自己的机制里来问参数。

## 登记类型

在自己的 `fabric.mod.json` 里声明入口 `meridian_origin`，指向一个实现 `OriginEntrypoint` 的类：

```java
public final class MyOriginTypes implements OriginEntrypoint {
    public static final OriginTalentType<StartingVolume> STARTING_VOLUME = OriginTalentType.oneShot(
            Identifier.fromNamespaceAndPath("my_mod", "starting_volume"), StartingVolume.CODEC,
            (player, params) -> learn(player, params.volume()));
    public static final OriginTalentType<Standing> STANDING = OriginTalentType.standing(
            Identifier.fromNamespaceAndPath("my_mod", "standing"), Standing.CODEC);
    public static final OriginThreatType<Feud> FEUD = new OriginThreatType<>(
            Identifier.fromNamespaceAndPath("my_mod", "feud"), Feud.CODEC);

    @Override
    public void register(OriginRegistrar registrar) {
        registrar.talent(STARTING_VOLUME);
        registrar.talent(STANDING);
        registrar.threat(FEUD);
    }
}
```

- 用入口而不是初始化时调静态方法：读出身数据包之前类型必须收齐，而各模组初始化的先后没有保证。
- 同一个类型 ID 登记两次，启动失败。
- 参数解析器是 Mojang 的 `Codec`，和原版数据包 JSON 同一套。字段的形状和范围在解析器里核对；要查「这个 ID 指的东西存不存在」这类事，等服务器启动以后自己核对。

## 两种天赋

| 种类 | 怎么建 | 什么时候起作用 |
|---|---|---|
| 一次性 | `OriginTalentType.oneShot(id, codec, grant)` | 玩家选定出身时调一次 `grant` |
| 常驻 | `OriginTalentType.standing(id, codec)` | 你在自己的机制里用 `Origins.talents(player, type)` 来问 |

一次性天赋的规矩：

- 发成以后记在玩家记录里，不会再调。
- `grant` 抛异常就不记，下次进服再试。所以 `grant` 要能重复执行而不出错，例如「已经会这一卷」不算失败。
- `grant` 可以改玩家的身体。框架在全部天赋发完以后才重新读身体记账。

## 查询

都在 `Origins` 上，只在服务端用：

| 方法 | 返回 |
|---|---|
| `chosen(player)` | 这个在线玩家选定的出身；没选、身体还没读到、出身已不在数据包里时为空 |
| `talents(player, type)` | 他的出身里这一类型的天赋参数，按 JSON 里的先后。读的是内存缓存，可以每 tick 调 |
| `threats(player, type)` | 同上，威胁 |
| `threatsByOrigin(type)` | 数据包里所有出身的这一类威胁参数，按出身 ID；没有这类威胁的出身不出现 |
| `all()` | 数据包里的全部出身 |
| `nameKey(originId)` | 出身显示名的语言键 |

- `Origins.CHOSEN` 事件：玩家定下出身、一次性天赋发过以后触发；调试指令直接定出身时也触发。
- `Origins.READY` 事件：玩家每次进服、身体读到以后触发一次，带上他的出身（没选是空）。玩家刚进服的头几个 tick 身体还没读到，这之前查到的都是空；要给已经选过出身的老玩家接上常驻的东西，听这个事件，不用自己等。
- 老玩家进服不触发 `CHOSEN`。

## 威胁

威胁类型只有 ID 和参数解析器，怎么兑现完全由登记它的附属管，进度也存在它自己的键下。

招敌引擎（Threatening）用的 `meridian_humanoid:crisis` 参数约定，见任务单 `ORIGIN-AND-CRISIS-TASK.md` 的「出身给招敌引擎的参数」一节；引擎做出来以后挪到 Threatening 的文档里。
