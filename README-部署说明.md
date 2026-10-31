# 阿尔比恩专精系统（AlbionMastery）— 部署说明

## 0. 先放依赖 jar

仓库里带了 Gradle Wrapper，但**不带任何第三方模组 jar**（它们的版权归各自作者，不能随源码再分发）。
编译前要自己下好放进 `libs/`，需要哪些见 [README](README.md) 的「从源码构建」一节。

## 1. 放置依赖 jar

把需要的模组 jar 直接丢进 `libs/`，**文件名随便叫**，不用改任何配置：

```
libs/tacz-1.20.1-1.1.7-hotfix2-all.jar
libs/lrtactical-1.20.1-0.4.1.jar
……
```

`build.gradle` 会自动扫描这个目录，把每个 jar 过一遍 `fg.deobf` 后当模组依赖引入。
`libs/` 在 `.gitignore` 里，放进去的 jar 不会被提交。

这些模组只是**编译期**要用（代码里引用了它们的 API）；运行期全部是可选依赖，
没装的来源不出现在专精树上。

### 依赖是怎么被引入的

`build.gradle` 在配置阶段会：

1. 扫描 `libs/` 下的所有 jar
2. 把每个 jar 复制一份到 `build/mod-deps/`，文件名规范化成 `<artifact>-<版本>.jar`
3. 用平铺仓库（flatDir）+ `fg.deobf` 引入它们

**为什么要绕这一圈？** ForgeGradle 只肯给「仓库形式」的依赖做反混淆，
`fg.deobf(files(...))` 会被直接拒绝：

```
Cannot deobfuscate dependency of type DefaultFileCollectionDependency
files(...) dependencies are not deobfuscated. Use a flatDir repository instead
```

拿到的就是没重映射过的正式版 jar。正式环境下 Minecraft 的方法名是 SRG（`m_21205_` 这种），
开发环境用的是 official 映射名（`getMainHandItem`），于是第三方模组 Mixin 里的
`@Shadow` 全部找不到目标，游戏一启动就崩在 `MixinApplyError`。

而平铺仓库是按 `<artifact>-<version>.jar` 精确匹配文件名的，libs 里的文件名五花八门
（`[TACZ]lrtactical-1.20.1-0.4.1.jar` 这种带中括号的、多段版本号的），没法直接当坐标用，
所以先规范化一遍：

```
[TACZ]lrtactical-1.20.1-0.4.1.jar
  -> build/mod-deps/_TACZ_lrtactical_1_20_1_0_4_1-1a08329bd5f.jar
  -> modlibs:_TACZ_lrtactical_1_20_1_0_4_1:1a08329bd5f
```

坐标里的「版本」是源文件大小 + 修改时间算出来的，**换 jar 坐标就变**，
不会命中 ForgeGradle 的旧反混淆缓存，也不用手动 clean。

同步时能在控制台看到：

```
[libs] 模组依赖: [TACZ]lrtactical-1.20.1-0.4.1.jar, tacz-1.20.1-1.1.7-hotfix2-all.jar
```

### Jar-in-Jar 不用管

TACZ 的 `-all.jar` 里内嵌了 mixinextras / luaj-core / luaj-jse / bcel / commons-math3。
这部分**不需要手工解包**——Forge 的 `JarInJarDependencyLocator` 在开发环境里
一样会处理，启动日志里能看到：

```
[JarInJarDependencyLocator]: Found 5 dependencies adding them to mods collection
```

### 在 runClient 里测枪包

枪包 / 刀包放到运行目录下的 `run/config/tacz/`（zip 或解压后的文件夹都行），
第一次启动 TACZ 会自己在那里生成默认枪包。

界面别硬打着测，直接：

```
/albionmastery setlevel 100
/albionmastery settypelevel 100
```

## 3. 运行时依赖

- Minecraft 1.20.1
- Forge 47.4.16+
- 不需要任何前置：配置界面用的 Cloth Config 已经内嵌在 jar 里
- 武器来源全部可选：TACZ、TACZ 近战附属（lrtactical）、拔刀剑、铁魔法、新生魔艺、匠魂、甜蜜魔法，
  原版和其它模组的近战武器、弓弩不装任何模组也有

服务端与客户端都要装：伤害结算在服务端，界面与提示在客户端。

## 4. 枪包适配情况

大类完全自动识别，装了什么读什么，不需要写任何配置：

| 包 | 命名空间 | 内容 | 本版本表现 |
|---|---|---|---|
| TWX-无尽驱动 | `twx` | 8 把枪 | ✅ 枪械大类 |
| 像素射击 经典 | `pixel_gun` | 90 把枪 + 10 近战 + 4 投掷 | ✅ 枪械大类 + 近战大类；投掷物不参与专精 |
| 三角洲刀包 | `delta_wt` | 12 把近战 | ✅ 近战大类 |
| 暗区突围·无限 刀包 | `e_ab_i_wt` | 9 把近战 | ✅ 近战大类 |

枪械与近战即使命名空间相同（比如像素射击包两样都有），也会拆成两个独立大类：
`tacz_gun/pixel_gun` 和 `lrtactical_melee/pixel_gun`。枪和刀共用一个熟练度池
在玩法上说不通，而且两者的伤害结算路径完全不同。

投掷物与消耗品（手雷、烟雾弹、绷带等）不纳入专精：它们的伤害不来自「使用者的武器熟练度」
这个模型，硬套进去只会让数值变得难以预期。

大类显示名的取值顺序：本模组语言文件覆盖 → 枪包的 `pack.<命名空间>.name` →
同名模组的显示名 → 命名空间原文。前三个都命中不了才会显示成 `twx` 这种原始 ID。

## 5. 提示文本

### 枪械

TACZ 枪械提示里那一行「伤害: xx」会被**直接替换为加成后的最终数值**，不再额外显示
「原值 → 加成后」。实现方式是 Mixin 重定向 `ClientGunTooltip.getText()` 里对
`AttachmentDataUtils.getDamageWithAttachment()` 的调用，排版、弹丸合计、爆炸伤害等
分支仍由 TACZ 自己处理，因此显示值天然包含配件修正。

本模组只额外追加一行摘要：

```
专精 Lv.42 · 大类 Lv.17 · 伤害 +105.7%
```

客户端配置开关：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `showMasteryTooltip` | true | 那一行摘要 |
| `detailedTooltip` | false | 打开后额外展开加成来源拆解（三行） |
| `overrideDamageDisplay` | true | 是否替换提示里的伤害 / 攻击力数值（枪械与近战都受此开关控制） |

### 近战武器

刀包的伤害走原版 `generic.attack_damage` 属性（`MeleeItem.attack()` 读玩家的攻击力
属性值再乘以这一击的 factor），提示里那行绿色的「+99 攻击伤害」就是从物品的属性
修饰符渲染出来的。所以近战这边不需要 Mixin，直接在客户端的
`ItemAttributeModifierEvent` 里改写主手的 ADDITION 修饰符，提示里显示的就是加成后的
攻击力。按百分比生效的修饰符（刀包里常见的移速加成）不会被动到。

换算用的是 `(基础攻击力 + 修饰值) * 倍率 - 基础攻击力`，因为原版在主手攻击力这一行
会把玩家的基础攻击力一起算进去显示，这样提示里的总值才正好等于最终伤害。吃原版属性的近战
（通用近战和匠魂，见 `WeaponKeys.VANILLA_ATTRIBUTE_MELEE_PROVIDERS`）的攻速、攻击距离特化
同样写进这块属性栏（攻速一行显示实际值，攻击距离多一行「+15% 实体范围」）。

**这个处理器只在客户端逻辑侧运行**（`EffectiveSide` 判断）：`ItemAttributeModifierEvent` 两边都会触发，
单人世界和局域网房主那里服务端线程也会走到客户端注册的监听器，改出来的修饰符会真的挂到实体身上，
专精伤害就被算了两次（拿剑的怪也会吃到玩家的加成）。

### 大类显示名

本模组不内置任何枪包 / 刀包的名字，取值链是通用的，按顺序尝试：

1. `albionmastery.category.<来源ID>.<命名空间>` —— 你自己的覆盖项
2. `pack.<命名空间>.name` —— TACZ 枪包的通用约定，`gunpack_info.json` 里的 `name` 就指向它
3. 同名模组的显示名
4. 命名空间原文

枪包基本都带 `gunpack_info.json`，所以第 2 步就能命中，显示成「TWX-无尽驱动」
「像素射击武器包」这类包内自带的名字。

刀包通常只有 `{"namespace": "xxx"}` 这一个元数据文件，没有名字可读，会回落到第 4 步
显示成 `delta_wt` 这种原始 ID。想改成中文，往资源包的语言文件里加一行即可，
不需要改模组：

```json
"albionmastery.category.lrtactical_melee.delta_wt": "三角洲刀包"
```

## 6. 目录说明

```
build.gradle / settings.gradle / gradle.properties
libs/                           放 TACZ 等第三方 jar（压缩包内为空）
src/main/resources/
  META-INF/mods.toml
  pack.mcmeta
  albionmastery.mixins.json     只含一个客户端 Mixin
  assets/albionmastery/lang/    zh_cn.json / en_us.json
src/main/java/com/flameforge/albionmastery/
  AlbionMastery.java            主类
  config/MasteryConfig.java     配置 + 可下发的数值快照
  weapon/WeaponKeys.java        武器键 / 大类键（双端共用）
  mastery/                      等级曲线、数据容器、Capability、加成计算
  network/                      全量同步 + 增量同步
  weapon/compat/                TACZ / lrtactical 的解析适配（按模组隔离）
  weapon/WeaponResolver.java    物品堆 -> 武器键的统一入口（双端）
  event/                        Capability 挂载、登录同步、枪械与近战战斗事件
  command/MasteryCommand.java   调试指令
  mixin/ClientGunTooltipMixin   覆盖 TACZ 提示里的伤害数值
  client/                       缓存、按键、提示文本、属性改写、专精界面
```

## 7. 玩法与配置

配置文件：`config/albionmastery-common.toml`（玩法数值，以服务端为准）、
`config/albionmastery-client.toml`（客户端表现）。

| 项 | 默认值 | 说明 |
|---|---|---|
| maxLevel | 100 | 所有专精初始 0 级，上限 100 |
| overallDamagePerLevel | 0.005 | 总体（内圈）每级 +0.5%，作用于这个来源的全部武器 |
| typeDamagePerLevel | 0.01 | 武器类型（中圈）每级 +1%，作用于该类型全部武器 |
| weaponDamagePerLevel | 0.02 | 单件武器（外圈）每级 +2%，只作用于自身 |
| weaponCrossDamagePerLevel | 0.001 | 单件武器每级 +0.1%，作用于同类型其它武器 |
| maxTotalDamageBonus | 100.0 | 总加成上限 +10000%，防数值失控的兜底 |
| xpBase / xpExponent | 80.0 / 1.0 | 升到第 L 级需 `80 * L^1.0`，满 100 级累计约 40 万 |
| xpPerMobExperience | 8.0 | 被杀生物每掉 1 点经验值给 8 点专精经验，**默认的主要经验来源** |
| xpPerDamage | 0.0 | 按造成的伤害给经验，默认关 |
| xpPerKill | 0.0 | 每次击杀的固定经验，默认关 |
| maxXpPerHit | 200.0 | 单次命中的经验上限，防刷 |

### 按武器来源调专精强度

`[mastery.sourceDamageMultiplier]` 里**每个来源一个独立的键**，乘的是专精等级算出来的伤害加成
（内圈 + 中圈 + 外圈 + 同类型联动），不碰特化本身的数值。全部默认 1.0，
即「完全保持专精原本的强度」。

| 键 | 管哪些武器 |
|---|---|
| taczGun | TACZ 枪械 |
| lrtacticalMelee | lrtactical 近战 |
| slashblade | 拔刀剑 |
| ironsSpellbooks | 铁魔法 |
| arsNouveau | 新生魔艺 |
| vanillaMelee | 通用近战（所有模组里的剑 / 斧 / 三叉戟 + 补录进来的近战） |
| tconstruct | 匠魂全家 |
| sweetmagic | 甜蜜魔法的魔法卷轴 |
| bow | 通用弓弩 |

键名不是一个个写死的，是照 `WeaponKeys.ALL_PROVIDERS` 按「来源 ID 转驼峰」生成的
（`irons_spellbooks` → `ironsSpellbooks`）。**以后新增来源，只要往 `ALL_PROVIDERS` 里加一行，
配置里就自动多一个默认 1.0 的独立键**，同步包、指令补全都不用再改——
谁也不会去蹭别的来源的倍率。

这一项以服务端为准，会随配置快照同步给客户端，面板上显示的加成和实际结算永远是同一个数。

> 从 2026-09-21 之前的版本升上来：老配置里的 `vanillaWeapon` 一键管着近战 + 弓弩 + 匠魂，
> 现在拆成了 `vanillaMelee`、`bow`、`tconstruct` 三个键。Forge 会删掉不认识的 `vanillaWeapon`
> 并按默认值 1.0 写入新键，**升级后记得把三个键按原来的数重新填一遍**。

## 8. 管理 / 调试指令

全部要求权限等级 2（服务器 OP，或单人世界开了作弊）。只敲 `/albionmastery` 会列出全部用法，点一行就能填进聊天框。

### 罗盘里的管理区

有权限时，罗盘左侧面板底部多一块「管理员」，只改自己的数据：

| 节点 | 按钮 |
|---|---|
| 中心 | 全部满级 / 全部清零 / 清除特化切换冷却 |
| 来源、类型 | 满级 / 清零 / −10 / +10（按住 Shift 变 ±1），整支（整类）满级 / 清零 |
| 武器 | 满级 / 清零 / −10 / +10，拿一把（枪附两组子弹，铁魔法给满级卷轴，魔艺给符文） |

清零类按钮要点两下确认。一次改动超过 8 条时只弹来源那一层的升级提示，不会刷屏。

### 等级

`<玩家>` 可以用 `@s`、`@a` 或玩家名；`<来源>` `<类型>` `<武器ID>` 都能 Tab 补全。

```
/albionmastery max <玩家>                                全部满级（服务端认得的所有武器、类型、来源）
/albionmastery max <玩家> held                           手上武器三层满级
/albionmastery max <玩家> source <来源>                  整个来源满级（来源 + 旗下类型 + 旗下武器）
/albionmastery max <玩家> type <来源> <类型>             整个类型满级
/albionmastery max <玩家> weapon <来源> <武器ID>         单件武器满级
/albionmastery level <玩家> all <等级>                   全部设成指定等级
/albionmastery level <玩家> held <等级>                  手上武器三层一起设
/albionmastery level <玩家> source|type|weapon ... <等级> 只设一个节点
/albionmastery wipe <玩家> [source <来源> | type <来源> <类型>]   清零，特化和学习点保留
```

「全部」用的是服务端自己的武器清单（TACZ 通用枪械索引、刀包索引、拔刀剑数据包注册表、法术和符文注册表、原版剑弓），
清单缓存 30 秒，`/reload` 和关服时清空。`/albionmastery weapon count` 可以看服务端各来源认得多少件。

### 武器与特化

```
/albionmastery weapon give <玩家> <来源> <武器ID>        发武器（背包满了掉在脚下，只有本人能捡）
/albionmastery weapon count                              服务端认得多少件武器
/albionmastery spec set <玩家> <来源> <类型> <栏位> <特化>  强制装特化，不看解锁和冷却（同系、重复照样拦）
/albionmastery spec clear <玩家> [<来源> <类型>]           卸下特化
/albionmastery spec cooldown <玩家>                      清除切换冷却
```

### 在线改配置

```
/albionmastery config spec <来源> <特化> [<项> [<值>]]   看 / 改某个特化的数值，列表里点一行就能改
/albionmastery config get <配置项>                       当前值、默认值、范围和注释
/albionmastery config set <配置项> <值>                  改完立刻生效并同步给所有在线玩家，同时写回配置文件
/albionmastery config reset <配置项>                     恢复默认
```

配置项路径就是配置文件里的层级用点连起来，Tab 能补全全部路径。**注意：** 用指令改过特化数值之后，
升级模组时 `autoUpdateSpecBalance` 仍会按版本把特化数值重置成新默认值，想保留就把它关掉。

### 老指令（作用于执行者自己手上那把）

```
/albionmastery info | addxp <数量> | setlevel <等级> | settypelevel <等级> | setoveralllevel <等级> | setall <等级> | reset
/albionmastery give <玩家> held <数量> | give <玩家> key <武器键> <数量>
/albionmastery study query|set|reset <玩家> [倍率]
```

## 9. 近战伤害是怎么接进去的

lrtactical 没有暴露任何服务端伤害事件（`MeleePreAttackEvent` 是纯客户端的，只带
`AbstractClientPlayer`），所以没走 Mixin，而是挂 Forge 的 `LivingHurtEvent`，
用「伤害的直接来源就是玩家本人」来锁定近战命中：

```java
if (source.getDirectEntity() != player) return;
```

弹丸、投掷物、爆炸的 `getDirectEntity()` 都不是玩家，因此枪械弹丸伤害不会在这里被
重复放大（那部分由 `EntityHurtByGunEvent.Pre` 处理）。枪械的近战突刺（枪托砸）
`getDirectEntity()` 确实是玩家，会按这把枪的专精加成——同一把武器，行为一致。

用 `LivingHurtEvent` 而不是 `LivingDamageEvent`，是为了让加成在护甲减伤之前生效，
语义上等同于「这把武器伤害变高了」，而不是「无视护甲额外补伤害」。

## 10. 已知限制

- 提示里的伤害替换依赖 TACZ 的 `ClientGunTooltip.getText()` 这一处内部实现。
  `mixins.json` 的 `defaultRequire` 是 1，也就是说如果将来 TACZ 改了这个方法，
  启动时会直接报 Mixin 注入失败而不是静默失效——报错信息会明确指到
  `ClientGunTooltipMixin`，改一行 target 即可。急着开服的话，把
  `albionmastery.mixins.json` 里 `client` 数组清空就能临时绕过。
- 爆炸类枪械提示里的「爆炸伤害」不受专精影响，因为本模组的加成只作用于
  `EntityHurtByGunEvent` 覆盖的弹丸伤害，显示与实际结算保持一致。
- 界面节点渲染真实 3D 枪械模型开销较大，已做视口裁剪 + 缩放阈值 + 只渲染展开的大类；
  低配机器可在客户端配置里关掉 `renderItemIcons`。
- 近战的属性改写只在客户端做。`ItemAttributeModifierEvent` 只给物品堆和装备槽，
  拿不到持有者，服务端无法判断该用谁的专精数据；实际伤害由服务端的 `LivingHurtEvent`
  结算，两边用的是同一套加成公式，不会出现显示与实际不符。
- 拔刀剑（SlashBlade）尚未接入，需要它的 jar 才能确认 API。接入方式与近战相同：
  加一个 compat 类 + 一个 Provider，`LivingHurtEvent` 那条路径不用改。

## 11. 专精特化

### 各武器的叫法

界面和提示里不说「特化」，按武器换成对口的词（`SpecTerms` + 语言键 `gui.albionmastery.term.<来源>.<词>`）：

| 来源 | 这一页 | 单项 | 两栏 | 分组 | 什么都不改 |
|---|---|---|---|---|---|
| TACZ 枪械 | 枪械改装 | 改装 | 主改装 / 副改装 | 部位 | 原厂配置 |
| 近战刀包 | 刀具改造 | 改造 | 主改造 / 副改造 | 部位 | 原厂 |
| 原版剑类 | 剑术与锻造 | 专长 | 主专长 / 副专长 | 方向 | 无专长 |
| 拔刀剑 | 拔刀修习 | 修习 | 主修 / 辅修 | 门类 | 无修习 |
| 铁魔法 | 法术铭刻 | 铭刻 | 主铭刻 / 副铭刻 | 类别 | 无铭刻 |
| 新生魔艺 | 法术书改良 | 改良 | 主改良 / 副改良 | 类别 | 不改良 |
| 弓弩 | 弓具改装 | 改装 | 主改装 / 副改装 | 部位 | 原厂弓具 |

代码和配置文件里仍统称「特化 / 特化」（`Specialization`），只是玩家看不到这个词。

### 通用配置和单独配置

- **中圈（类型）上设的是通用配置**：这一类下面没单独配置的武器都用它。
- **外圈（单件武器）可以单独配置**：只要这件有单独配置，就整套用它自己的（两栏一起，不和通用配置按栏混用）；没有才用通用的。
- 第一次在武器页点卡片时，先把通用配置整套抄过来作为起点，再改点的那一栏；武器页左栏顶上的「恢复通用配置」会删掉单独配置。
- 解锁门槛两种配置一样，都看类型和来源的等级；冷却按「配置 + 栏」各记各的。
- 存储：和通用配置在同一张表里，键是武器键（第二栏带 `@2`）；单独配置里的空栏存成制式特化的键，两栏都空也认得出是单独配置。
- 罗盘里单独配置过的武器，悬停提示会多一行「已单独配置…」；类型面板会提示有几件武器单独配置了。

### 系别：两个槽怎么搭配

每个特化属于一个系别（枪械就是改装部位），**两个槽不能装同系的特化**，界面上按系别分组显示：

| 来源 | 系别 |
|---|---|
| TACZ 枪械 | 弹匣 / 弹药 / 枪管枪口 / 枪机 / 枪托握把 / 训练 / 装具 |
| 近战刀包 | 握柄 / 刀身 / 刃口 / 战法 / 保养 |
| 原版剑类 | 剑身 / 剑刃 / 剑柄 / 剑术 |
| 拔刀剑 | 刀装 / 幻影 / 剑技 / 心法 |
| 铁魔法 | 媒介 / 墨水 / 符文 / 章节 / 咒印 |
| 新生魔艺 | 催化 / 书写 / 书带 / 书页 / 刻印 / 形态 |
| 弓弩 | 弓臂 / 弓弦 / 箭头 / 箭杆 / 箭囊 |

同系的特化方向相近（比如几种弹匣都在调容量和换弹），只能二选一；两个槽要从不同的系里挑，才是真正的搭配。
规则之前存下的同系组合（比如扩容弹匣 + 弹鼓）不会被删，但第二槽不生效，卡片上会标「同组未生效」。

### 各来源的专属维度

对人、对怪、破甲、残血、吸血、连击、练级这些通用路子每个来源都有，但一定配上本来源独有的维度，
不做数字一模一样的换皮：

| 来源 | 专属维度 | 挂在哪 |
|---|---|---|
| TACZ 枪械 | 射速、开镜、腰射/开镜散布、掏枪、拉栓、冲刺后开火、免耗弹、穿透、弹匣、换弹、后座、弹速、枪声、爆头 | 见下一小节 |
| 近战刀包 | **背刺**（目标身后约 120° 扇形）、击退、攻速、耐久 | `SpecializationEffects.targetMultiplier`、`LivingKnockBackEvent`、`MeleeAttackSpeedMixin` |
| 原版剑类 | **攻击距离**（Forge `ENTITY_REACH` 属性）、击退、攻速、受到伤害 | `SpecMoveSpeed`、`LivingKnockBackEvent`、`LivingHurtEvent` |
| 匠魂 | 按武器形制（剑 / 匕首 / 斩斧 / 战锤 / 长柄）分类型；**攻击距离**和攻速和原版剑类走同一套属性（`WeaponKeys.VANILLA_ATTRIBUTE_MELEE_PROVIDERS`）、击退、受到伤害 | `SpecMoveSpeed`、`MeleeCombatHandler` |
| 甜蜜魔法 | 按卷轴元素分类型；法力、冷却、伤害 | `SweetMagicCostMixin`、`SweetMagicCombatHandler` |
| 拔刀剑 | **SA 伤害**（Drive、审判斩，以及 SA 发动后 1.5 秒内的斩击）、**耀魂获取**、刀身斩击/幻影剑分开算、击退（拔刀剑自己的击退逻辑多数时候会取消击退，所以这一项基本只对重击和召唤剑有效） | 全部在 `LivingHurtEvent` 命中时一处算；`PerformSlashArtEvent`、`AddProudSoulEvent` |
| 铁魔法 | 冷却、前摇、法力、双重施法、背水（血契咒印） | 铁魔法自己的事件 + `SpellCastTimeMixin` |
| 新生魔艺 | **投射物飞行速度、穿透**；一个法术用到几种类型的符文，这几种类型的特化合并后完整生效一次（伤害、法力、暴击、吸血等都一样） | `ArsNouveauSupport.spellSpec`，投射物在 `EntityJoinLevelEvent` 里的 `EntityProjectileSpell` |
| 弓弩 | 拉弓速度（**弩的上弦也吃**）、箭速、**箭的爆头**（命中点在头部）、**穿透**、**省箭**（射之前和射之后核对背包，箭确实少了一支才按概率退还，退了的那支射出去不可拾取；无限附魔、模组无限弓、箭袋饰品都刷不了）、击退 | `ArrowLooseEvent`、`CrossbowDrawMixin`、`BowCombatHandler` |

「受到伤害」对所有手持武器的来源都生效（防弹插板、战壕刀、决斗剑术、守势剑术、见切、神速）。

**命中后的效果算哪件武器**（吸血、点燃/缓慢/虚弱、击杀回血、嗜战）由 `DamageAttribution` 按伤害的来路认，
不看主手：铁魔法按伤害源里的法术，新生魔艺按正在结算的法术主效果，箭和三叉戟按射出时记下的武器，
TACZ 子弹按子弹自己带的枪 ID，亲手挥砍要求伤害类型是原版「玩家攻击」且主手是近战武器，
拔刀剑的斩击特效和召唤剑要求主手还是拔刀剑。荆棘、爆炸、着火之类认不出的一律不触发。

几个跨来源的通用机制，用来做「有意思」的特化：

| 机制 | 说明 | 用在哪些特化 |
|---|---|---|
| 嗜战 `frenzyStep` / `frenzyMax` | 每次击杀叠一层伤害加成，8 秒不再击杀清零（`FrenzyTracker`） | 肾上腺素、嗜血本能、修罗、灵魂收割、魔源涌流、凯旋剑术 |
| 击杀回血 `killHeal` | 击杀回复最大生命值的一定比例 | 肾上腺素、灵魂收割、凯旋剑术 |
| 近 / 远距离 `nearDamage` / `farDamage` | 目标在 6 格内 / 24 格外时的伤害倍率 | 破门战术、远距射术、远咏章节、远射书页、鹰眼弓弦 |
| 命中效果 `igniteChance` / `slowChance` / `weakenChance` | 按概率点燃 4 秒 / 缓慢 II 3 秒 / 虚弱 4 秒，同一 tick 对同一目标只掷一次 | 燃烧弹、淬毒刃、雷切、烈焰剑刃、火矢箭头、霜寒箭杆 |
| 残弹 `lastRoundDamage` | 枪械弹匣只剩最后三成时的伤害倍率 | 压底重弹 |

说明文字里的叫法跟着各模组官方中文走：TACZ 的「开镜时间 / 腰射扩散 / 跑射延迟」，
铁魔法和魔艺把「伤害」叫「法术强度」，魔艺的法力叫「魔源」，拔刀剑的特殊攻击就叫「SA」、刀魂叫「耀魂」。
同一个维度要在某个来源上换叫法，在语言文件里加 `gui.albionmastery.spec_stat.<来源>.<项>` 就行。

### 枪械的手感维度

枪械特化还能改 TACZ 的操枪手感。每一项都在 TACZ 算完配件修正之后再乘，所以和配件叠加，互不覆盖：

| 配置项 | 含义 | 挂在 TACZ 哪里 |
|---|---|---|
| `fireRate` | 射速倍率，>1 更快；最快 1200 发/分 | `GunData.getShootInterval`（两端共用，服务端校验跟着变） |
| `adsTime` | 开镜耗时倍率，<1 更快 | 服务端 `LivingEntityAim.tickAimingProgress` + 客户端 `LocalPlayerAim.getAlphaProgress` |
| `hipSpread` / `aimSpread` | 腰射 / 开镜散布倍率，<1 更准 | `ModernKineticGunScriptAPI.shootOnce` 里算完的散布 |
| `drawTime` | 掏枪耗时倍率 | `LivingEntityDrawGun.getDrawCoolDown`（同步给客户端） |
| `boltTime` | 拉栓耗时倍率 | `ModernKineticGunScriptAPI.getBoltTime` |
| `sprintTime` | 冲刺后恢复射击的耗时倍率 | `LivingEntityAim.tickSprint` |
| `ammoSave` | 每发不耗弹匣的概率，上限 0.9 | `ModernKineticGunScriptAPI.reduceAmmoOnce` |
| `pierce` | 子弹额外穿透数 | `EntityKineticBullet` 构造器 |

这些动作的动画也按同一个倍率播放，见第 13 节。点射模式的连发间隔同样跟着射速走
（`GunRateScaling` 把射手带进 `BurstData` 那几处拿不到射手的调用里）。
不覆盖的只剩：背包直读枪和栓动枪的免耗弹（前者没有弹匣，后者省了等于不用拉栓）。

### 特化说明是按数值自动生成的

专精界面里每个特化的「+ 得到 / − 付出」两行不写在语言文件里，而是客户端拿服务端下发的
实际数值现生成（`SpecDescriber`）。服主改了 `albionmastery-common.toml` 里的数，
玩家看到的说明就跟着变；给特化加维度只需要在 `SpecDescriber` 登记一行、语言文件加一个 `spec_stat` 标签。

### 特化数值的版本迁移

Forge 配置只会**补上缺的项**，已经写在文件里的旧值不会被新默认值覆盖。为了让重新平衡能真正落到老服务器上，
`[mastery.specialization]` 里有两项：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `autoUpdateSpecBalance` | true | 模组更新了特化数值时，自动换成新默认值 |
| `specBalanceVersion` | 模组维护 | 小于代码里的 `MasteryConfig.SPEC_BALANCE_VERSION` 且上一项开着时，启动时把全部特化数值重置为新默认值，再写成当前版本 |

**自己改过特化数值、想保留的服主，升级前把 `autoUpdateSpecBalance` 改成 false。**
以后改 `defineSpec` 里的默认值，记得把 `SPEC_BALANCE_VERSION` 加一。

## 12. 排行榜搜索

- 排行榜顶栏右端有搜索框（Ctrl+F 聚焦），输玩家名的一部分或 UUID 开头（至少 8 位，带不带横线都行），停手 0.6 秒或按回车就搜，点「×」回到整张榜。
- 搜索在服务端的整张榜里做，前 `topCount` 名以外的人也能搜到；显示的是他在整张榜上的真实名次，切时间范围、排序方式时保留搜索词。
- 搜索和普通刷新共用 `requestCooldownSeconds` 限流，客户端会等冷却结束再自动发，不会被刷屏。

## 13. 哪些武器算「可专精」

按这个顺序认，认不出来就不是武器——不去猜「这东西有攻击力所以大概是武器」，否则镐子锄头和一堆有攻击力的道具会一起进树：

1. **补录表**（`weapon/compat/WeaponOverrides.java`）：内置 + 配置文件，见下。
2. **原版物品类**：`SwordItem` / `AxeItem` / `TridentItem` / `BowItem` / `CrossbowItem`。
3. **模组自己打的标签**：`forge:tools/swords|axes|axe|tridents|bows|crossbows`（`axe` 是单数拼法，灾变用的就是这个）。

### 为什么需要补录表

很多模组的武器有自己的基类，不继承原版武器类，第 2、3 层都收不到：

| 模组 | 情况 |
|---|---|
| 灾变 L_Ender's Cataclysm | 14 件武器继承自 `Cataclysm_Weapon_Item extends Item`，只有 athame、khopesh、黑钢剑是 `SwordItem`；模组自己只给 6 件打了 `forge:tools/*` |
| 诡厄巫法 Goety | 镰、锤、匕首、冰镐是 `TieredItem` 直接子类（剑和斧倒是正常） |
| 额外植物学 | 9 把锤继承 `PickaxeItem` |
| 血肉重塑 | 爪是 `TieredItem` |

这些都写在内置表里，装了对应模组就生效，玩家什么都不用做。

### 匠魂：单独一个来源

匠魂全套武器都是 `ModifiableItem extends TieredItem`，一件都不继承原版武器类，原来一个也收不到。
现在它是独立来源，中圈按**武器形态**分六类：剑、匕首、巨刃、斧、重锤、长柄——不按材料，
同一个 `tconstruct:sword` 不管用什么材料做都是同一件武器。

- 判定以匠魂自己的 `tconstruct:modifiable/melee/primary` 标签为准（这张表正好是「真近战武器」，
  镐、挖沟机、锄镐、砍刀、钓竿只在 `melee/weapon` 里，天然排除），认不出的附属武器按
  `melee/sword` / `parry` / `broad` / `small` 兜底。不需要加编译依赖。
- 工匠创新、魂樱工匠、匠魂杂物往同一批标签里加的武器自动收录；它们塞进近战表里的激光枪、左轮、
  法术书、弹药、盾，以及可堆叠的手里剑 / 投斧（堆叠时匠魂会取消整次攻击）都已排除。
- 匠魂拔刀的 `ticex:reforged_slashblade` 归拔刀剑来源，不会两边各加一次。
- 长弓和短弩留在通用弓弩来源：它们本来就打了 `forge:tools/bows|crossbows`，拉弓速度那些特化直接生效。
- 特化 14 张，四个部位：锻头 / 手柄 / 镶嵌 / 锻打。
- **已知不生效**：耐久特化。匠魂的 `damageItem` 返回 0，走不到本模组的耐久钩子，是静默失效（不会弄坏工具）。
- **升级影响**：匠魂把 `sword / dagger / cleaver / swasher` 塞进了原版 `minecraft:swords` 标签，
  所以它们**以前**被算作「通用近战 · 剑」。现在归到匠魂来源，键变了，这几把上已练的等级会成为孤儿数据。

### 甜蜜魔法：卷轴是武器，法杖是载体

和铁魔法（法术是武器）、新生魔艺（符文是武器）一个思路。中圈按元素分（炎、霜、风、雷、地、光、暗、水、
重力、爆破、剧毒、时空……），外圈是每一张魔法卷轴。

- 施法时开一个「谁在放哪张卷轴」的窗口（注在法杖类的右键和松手两个入口上，因为施法逻辑写在
  `IWand` 接口的默认方法里，而 Mixin 不支持注入接口）。窗口期间生成的弹体和召唤物会被写上卷轴 id，
  存进实体持久数据，跨区块、存档都不丢，命中时读回来。
- 伤害按卷轴的专精和特化加成；法力消耗（`getUseMF`）和冷却（`getMaxRecastTime`）按特化缩放，
  「够不够放」的判断用的是同一个数，判断和实际扣除一致。
- 击杀经验给法杖当前选中的那张卷轴。
- 特化 15 张，四个部位：杖芯 / 术式 / 媒介 / 修习。
- **已知不生效**：蓄力时间特化（那个速率常量在接口默认方法里，注不进去）；物品提示里显示的法力消耗
  是卷轴本身的基础值，不含特化折扣（实际扣除是按特化算的）。

### 服主怎么增删

改 `albionmastery-common.toml`，不用数据包：

```toml
# 补录成武器：「物品id=大类」，大类可填 sword / axe / trident / other / bow / crossbow
# 只写 id 不写大类时按 other（其它近战）算
extraWeapons = ["examplemod:big_sword=sword", "examplemod:war_scythe"]

# 误收进树的东西写这里，优先级最高
notWeapons = ["examplemod:decorative_blade"]
```

这两项会随服务端配置快照同步给客户端，两端认的是同一张表，不会出现「服务端算的武器键客户端对不上、界面永远 0 级」。

新增的「其它近战」大类（`albionmastery.type.vanilla_melee.other`）和剑、斧、三叉戟同属通用近战来源，
共用同一套特化（剑的专长），不需要另配一份特化数值。

## 14. 动画与画面同步

凡是特化改了时长的动作，画面就按同一个倍率播——逻辑快了画面没快，玩家会以为特化没生效。
**别人也看得见**：倍率做成了几个会自动同步的玩家属性（`SpecVisualAttributes`），
服务端一改，所有看得见这个玩家的客户端都会收到，不用自己发包。

| 动作 | 特化项 | 画面怎么跟上 |
|---|---|---|
| 拉弓 / 上弦 | `drawSpeed` | 直接改「已经拉了多久」这个计时本身（`UseItemSpeedMixin`）。弓弦模型、第一人称手臂、第三人称上弦姿势、拉弓视野收缩、弩的上弦音效、松手时的蓄力全部由它推出来，所以一处改完全部对齐，自己和别人看到的都一样 |
| 换弹 / 拉栓 / 掏枪 | `reloadTime` / `boltTime` / `drawTime` | 第一人称动画按段变速（`AnimationSpeeds`）；音效同时变调（`GunSoundMixin`）；第三人称的上半身换弹动作按同步来的倍率播（`GunThirdPersonAnimMixin`） |
| 开火 | `fireRate` | 枪跳（后坐）动画跟着射速 |
| 开镜 / 收镜 | `adsTime` | `aim_start` / `aim_end` 跟着开镜进度 |
| 冲刺收枪、举枪 | `sprintTime` | `run_start` / `run_end` 跟着「冲刺后多久能开火」 |
| 近战挥砍 | `attackInterval` | 第一人称挥砍动画 + 第三人称上半身动作；带突进的刀，突进时机也跟着出手快慢走 |
| 长吟唱施法 | `castTime` | 施法前摇动作跟着吟唱时间（`SpellCastAnimMixin`）；瞬发和持续引导不动，它们的施法时间本来就不改 |

### 动画是怎么分段定速的

第一人称的枪械 / 近战动画走的是 TACZ 的动画系统。同一时刻有好几条轨道在跑（待机、行走、瞄准、枪跳……），
所以**不能**给「当前这一刻」定一个统一倍速——那会把走路摆枪一起抖起来。
现在每段动画在开始播的那一刻各自定一次速，之后不变：

1. 先按动画名认（`reload_*`、`bolt`、`draw`、`shoot`、`aim_*`、`run_start` / `run_end`、`melee_*`）；
2. 名字不认识（第三方枪包自己起的名）就看它是被哪个输入触发的——`reload`、`blot`（TACZ 自己拼错的拉栓）、
   `draw`、`shoot`、`aim`、`attack_left` / `attack_right`，这些输入名对任何枪包都一样；
3. 两层都认不出来就按原速播。宁可不变速，也不要把待机、行走这些循环动画弄错。

待机、行走、跑步循环、检视、收枪、切火力模式、转管枪旋转、空仓挂机姿势，以及枪自带的刺刀 / 枪托近战，
都明确不变速（它们没有对应的特化）。

### 已知的没跟上的地方

- 第三人称的**开火**动作不变速：射速本来就可能比开火动作短，原版 TACZ 装射速配件也是这个样子。
- 别人**开镜、冲刺**的第三人称姿势由 TACZ 自己按同步进度插值，本来就跟着服务端走，没有单独处理。
- 音效的音调最高只能到 2 倍（引擎上限），倍率再高音效也跟不上了。
- 原版近战的挥手动画固定 6 tick，原版就和攻速无关，没有改。
