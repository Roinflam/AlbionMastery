# 阿尔比恩专精系统（AlbionMastery）— 部署说明

## 0. 这个压缩包不是开箱即编译的

解压后是一个完整的源码工程，但**缺少 Gradle Wrapper**（`gradlew`、`gradlew.bat`、
`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`）。
wrapper 里的 jar 是二进制文件，没法随源码一起给。补齐方式任选其一：

- **最省事**：从你现有的 worldlevel 工程根目录，把 `gradlew`、`gradlew.bat`、
  `gradle/` 整个目录复制过来。两个工程用的是同一套 Forge 1.20.1 MDK，wrapper 完全通用。
- 或者本机装了 Gradle：在项目根目录执行 `gradle wrapper --gradle-version 8.1.1`
- 或者直接用 IntelliJ 打开，让 IDE 用自带的 Gradle 跑

另外 `libs/` 目录是空的，必须先放 TACZ 的 jar（见下一节），否则编译不过。

## 1. 放置依赖 jar

把需要的模组 jar 直接丢进 `libs/`，**文件名随便叫**，不用改任何配置：

```
libs/tacz-1.20.1-1.1.7-hotfix2-all.jar
libs/TACZ_lrtactical-1.20.1-0.4.1.jar
```

`build.gradle` 会自动扫描这个目录，把每个 jar 过一遍 `fg.deobf` 后当模组依赖引入。
以后接拔刀剑之类的，同样只要把 jar 丢进来就行。

TACZ 编译期必需。lrtactical 编译期也必需（近战代码要引用它的 API），
但运行期是**可选依赖**，服务器不装照样跑，只是刀包部分不出现。

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
/albionmastery setcatlevel 100
```

## 3. 运行时依赖

- Minecraft 1.20.1
- Forge 47.4.16+
- TACZ 1.1+（强制依赖）
- lrtactical 0.4+（可选依赖，装了才有刀包）

服务端（Mohist 混合端同样适用）与客户端都要装：伤害结算在服务端，界面与提示在客户端。

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
会把玩家的基础攻击力一起算进去显示，这样提示里的总值才正好等于最终伤害。

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
| categoryDamagePerLevel | 0.01 | 大类每级 +1%，作用于该大类全部武器 |
| weaponDamagePerLevel | 0.02 | 单件武器每级 +2%，只作用于自身 |
| weaponCrossDamagePerLevel | 0.001 | 单件武器每级 +0.1%，作用于同大类其它武器 |
| maxTotalDamageBonus | 10.0 | 总加成上限 +1000%，防数值失控的兜底 |
| xpBase / xpExponent | 40 / 1.25 | 升到第 L 级需 `40 * L^1.25`，满 100 级约累计 18 万 |
| xpPerDamage | 1.0 | 每 1 点基础伤害 1 点经验 |
| xpPerKill | 20.0 | 每次击杀额外 20 点经验 |

## 8. 调试指令

需要权限等级 2，作用于执行者自己，手持目标枪械时使用：

```
/albionmastery info                  查看当前手持枪械的专精明细
/albionmastery addxp <数量>          给当前手持枪械加经验
/albionmastery setlevel <等级>       直接设定当前手持枪械的专精等级
/albionmastery setcatlevel <等级>    直接设定所属大类的专精等级
/albionmastery reset                 清空全部专精数据
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
