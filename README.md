<div align="center">

<img src="https://cdn.modrinth.com/data/cached_images/6d4fe62679315f9998eea2225731a16ae3a1ec5b_0.webp" alt="Albion Mastery" width="800">

# Albion Mastery | 阿尔比恩专精

[![CurseForge Downloads](https://cf.way2muchnoise.eu/full_albion-mastery_downloads.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/albion-mastery)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/albionmastery?style=for-the-badge&logo=modrinth&label=Modrinth)](https://modrinth.com/mod/albionmastery)
[![MC Version](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?style=for-the-badge&logo=minecraft)](https://minecraft.net)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange?style=for-the-badge)](https://neoforged.net)
[![License](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey?style=for-the-badge)](LICENSE)

[![GitHub](https://img.shields.io/badge/GitHub-Roinflam-181717?style=for-the-badge&logo=github)](https://github.com/Roinflam)
[![Bilibili](https://img.shields.io/badge/Bilibili-@Roinflam-00A1D6?style=for-the-badge&logo=bilibili)](https://space.bilibili.com/285030707/)

</div>

<details open>
<summary><h2>English</h2></summary>

**Albion Mastery** is a weapon mastery system for Minecraft 1.21.1 NeoForge. It finds the weapons in the mods you already have installed (guns, melee weapons, blades, spells, bows) and lets every weapon level up as you fight with it. Mastery is tracked on three rings, from the weapon source to the weapon type to the single weapon, and each ring gives a damage bonus. Once a weapon type is trained far enough you can pick specializations that trade one strength for another.

### Features

- **Three mastery rings**: source (for example TACZ guns) → type (rifles) → single weapon. Kills give XP to all three; the inner rings need more XP per level.
- **Specializations**: every weapon type has up to four slots (normal, breakthrough, normal, breakthrough). Each source has its own options with real gains and costs: fire rate, ADS time, recoil and spread for guns; reach and backstab for swords; SA damage and proud souls for SlashBlade; mana cost, cooldown and double casting for spells, and more. Gains and costs grow together as you level a specialization.
  - **Long-term progression**: normal specializations level 1–5 with mod points earned from source level (plus a few from Mastery Rank); a maxed one can be pushed through to ★1–★5 with breakthrough points earned from type level and maxed weapons.
  - **Weapon gate**: the first slot opens once a single weapon reaches its level cap; higher ranks lower that requirement, and a fully trained type skips it. Slots 2–4 open with type and source level.
  - **Respec**: refund all mod points of a source or all breakthrough points of a type at any time (the first time is free, then a 24-hour cooldown).
  - **Three page layouts**: left slot column, top slot bar (default) or list + detail. Switch with the button in the top bar; hover a card for the per-level table.
  - Slot count, unlock thresholds, per-level scaling and costs, point income, rank reduction, per-weapon XP and damage multipliers are all configurable.
- **Mastery Rank**: an account-wide rank that grows with the total level of all your weapons, with a promotion animation and a road view of every rank.
- **Leaderboards**: overall, daily, weekly and monthly. Click a player to see their whole mastery map and specializations.
- **Automatic weapon detection**: installed gun packs and blade packs are read automatically; sources that aren't installed simply don't appear. Server owners can add or exclude items in the config.
- **Server authoritative**: all bonuses are applied on the server; the client only displays synced data.
- **In-game config screen**: Mods → Albion Mastery → Config. Every option of the config files is listed there with its range and description.

### Supported weapon sources

All optional; install any combination:

- TACZ (guns) and the LR Tactical melee addon
- SlashBlade Resharped and its addons
- Iron's Spells 'n Spellbooks (spells)
- Ars Nouveau (spell glyphs)
- Iron's Arms 'n Artifice (guns)
- Vanilla and modded melee weapons, bows and crossbows (no mod needed)

FTB Quests is also optional: with it installed you get a "Mastery Rank" task type.

### Usage

- Press **B** to open the mastery screen (rebindable in Controls). Drag to pan, scroll to zoom, click a node for details.
- Operators can use `/albionmastery help` for the management and debug commands.

### Requirements

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 or newer |

Install on both the server and the client. No other mod is required; Cloth Config is bundled.

### Building from source

The repository does **not** include third-party mod jars (they belong to their authors and can't be redistributed). Download the following NeoForge 1.21.1 jars yourself and put them into `libs/` (any file name works, the build script picks up every jar in that folder):

| Mod | Version used here |
| --- | --- |
| TACZ | 1.2.0 |
| LR Tactical | 0.4.3 |
| SlashBlade Resharped | 2.1.9 |
| Iron's Spells 'n Spellbooks | 3.16.3, plus Iron's Lib 2.3.0, GeckoLib 4.9.3, playerAnimator 2.0.4, Curios 9.5.1 |
| Ars Nouveau | 5.13.5 |
| Iron's Arms 'n Artifice | 1.1.1 (needs Iron's Lib and GeckoLib) |
| FTB Quests | 2101.1.38, plus FTB Library 2101.1.39, FTB Teams 2101.1.12, Architectury 13.0.11 |

Then build with JDK 21:

```bash
./gradlew build
```

The jar is in `build/libs/`, with Cloth Config bundled inside.

More details for server owners and developers (config, commands, how each source is hooked in) are in [`README-部署说明.md`](README-部署说明.md) (Chinese).

### License and credits

- Licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). Full text: [LICENSE](LICENSE). Free to use, modify and include in modpacks; no commercial use; redistributed or modified versions must credit the original author (Roinflam), state what was changed, and use the same license.
- The mastery system is inspired by the Destiny Board of *Albion Online*; the Mastery Rank rules, rank titles and emblem designs are based on the Mastery Rank of *Warframe*. Albion Online is a trademark of Sandbox Interactive GmbH and Warframe is a trademark of Digital Extremes Ltd. This is an unofficial fan-made mod and is not affiliated with or endorsed by them.
- The names of the supported mods belong to their respective authors. This mod contains no code or assets from them; when they are installed it only calls their classes and applies mixins to them at runtime.
- Bundles [Cloth Config API](https://github.com/shedaniel/cloth-config) by shedaniel (GNU LGPLv3), unmodified, as a separate jar-in-jar file (`META-INF/jarjar/`), only for the config screen. It keeps its own license and can be replaced with any other compatible build; the LGPLv3 and GPLv3 texts are in [`licenses/`](licenses) and in the jar under `META-INF/licenses/`.

</details>

<details>
<summary><h2>中文</h2></summary>

**阿尔比恩专精**是 Minecraft 1.21.1 NeoForge 的武器专精系统。它会自动找出你已经装的模组里的武器（枪、近战、拔刀剑、法术、弓弩），用哪件武器战斗，哪件武器就升级。专精分三圈：武器来源 → 武器类型 → 单件武器，每一圈都给伤害加成；某个武器类型练到一定程度后，还能选特化，用一种长处换另一种。

### 特色

- **三圈专精**：来源（如 TACZ 枪械）→ 类型（步枪）→ 单件武器。击杀给三圈同样的经验，越往内圈每级要的经验越多。
- **特化**：每个武器类型最多 4 个槽（普通、突破、普通、突破），每个来源都有自己的一套选项，项项有得有失：枪械有射速、开镜时间、后座、散布，剑有攻击距离、背刺，拔刀剑有 SA 伤害、耀魂，法术有法力消耗、冷却、双重施法等等。特化升级时收益和代价按同一比例一起涨。
  - **长期养成**：普通版 Lv1～5，花来源等级给的改装点（段位高了每个来源再多几点）；满级后可以突破到 ★1～★5，花类型等级和满级武器给的突破点。
  - **武器门槛**：第一个槽要单件武器练满才开，段位越高要求越低，整个类型练透了直接免门槛；第 2～4 槽看类型和来源等级。
  - **退点**：随时可以把一个来源的改装点或一个类型的突破点全部退回重配（第一次免费，之后冷却 24 小时）。
  - **三种布局**：左栏槽位、顶部槽位条（默认）、列表 + 详情，顶栏按钮随时切换；鼠标停在卡片上能看每一级的数值表。
  - 槽数、开槽门槛、每级系数和价格、点数来源、段位减免、单件武器的经验 / 伤害倍率都能在配置里改。
- **段位**：账号级的总进度，跟着全部武器的等级总和上涨，有晋升动画，还能看所有段位排成的段位之路。
- **排行榜**：总榜、日榜、周榜、月榜，点进去能看别人的整张专精图和选的特化。
- **自动识别武器**：装了什么枪包、刀包就读什么，没装的来源不出现；服主可以在配置里补录或排除物品。
- **服务端权威**：所有加成在服务端结算，客户端只显示同步下来的数据。
- **游戏内配置界面**：模组列表 → 阿尔比恩专精 → 配置，配置文件里的每一项都列在里面，带取值范围和说明。

### 支持的武器来源

全部可选，装哪个接哪个：

- TACZ（枪械）及其近战附属 LR Tactical
- 拔刀剑（SlashBlade Resharped）及其附属
- 铁魔法（Iron's Spells 'n Spellbooks）
- 新生魔艺（Ars Nouveau）
- Iron的武备与巧械（Iron's Arms 'n Artifice，枪械）
- 原版和其它模组的近战武器、弓弩（不用装任何模组）

FTB 任务也是可选的：装了以后多一种「专精段位」任务。

### 怎么用

- 按 **B** 打开专精界面（可在按键设置里改）。拖动平移、滚轮缩放、点节点看详情。
- 管理员输入 `/albionmastery help` 查看管理和调试指令。

### 运行需求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 及以上 |

服务端和客户端都要装。不需要任何前置，Cloth Config 已经内嵌。

### 从源码构建

仓库里**不带**第三方模组的 jar（版权归各自作者，不能随源码再分发）。请自己下载下面这些 NeoForge 1.21.1 版 jar 放进 `libs/`（文件名随意，构建脚本会扫描整个目录）：

| 模组 | 本仓库用的版本 |
| --- | --- |
| TACZ | 1.2.0 |
| LR Tactical | 0.4.3 |
| 拔刀剑 SlashBlade Resharped | 2.1.9 |
| 铁魔法 Iron's Spells 'n Spellbooks | 3.16.3，以及 Iron's Lib 2.3.0、GeckoLib 4.9.3、playerAnimator 2.0.4、Curios 9.5.1 |
| 新生魔艺 Ars Nouveau | 5.13.5 |
| Iron的武备与巧械 Iron's Arms 'n Artifice | 1.1.1（要 Iron's Lib 和 GeckoLib） |
| FTB 任务 FTB Quests | 2101.1.38，以及 FTB Library 2101.1.39、FTB Teams 2101.1.12、Architectury 13.0.11 |

然后用 JDK 21 构建：

```bash
./gradlew build
```

产物在 `build/libs/`，里面内嵌了 Cloth Config。

给服主和开发者的详细说明（配置、指令、各来源怎么接入）见 [`README-部署说明.md`](README-部署说明.md)。

### 许可与声明

- 本模组采用 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)（知识共享 署名-非商业性使用-相同方式共享 4.0 国际）协议，全文见 [LICENSE](LICENSE)：可以免费使用、修改、加入整合包；不得用于商业用途；转载或发布修改版需署名原作者 Roinflam、注明改动，并沿用同一协议。
- 专精玩法的灵感来自《阿尔比恩 Online》（Albion Online）的命运之板；段位的规则、称号和徽章造型参考《星际战甲》（Warframe）的精通段位。Albion Online 是 Sandbox Interactive GmbH 的商标，Warframe 是 Digital Extremes Ltd. 的商标。本模组为非官方同人作品，与它们无关联，也未获其认可。
- 文中提到的各个模组名称归其作者所有。本模组不含它们的任何代码或素材，只在它们装着时于运行期调用其类、对其应用 Mixin。
- 内嵌 shedaniel 的 [Cloth Config API](https://github.com/shedaniel/cloth-config)（GNU LGPLv3），未作修改，以独立 jar-in-jar 文件的形式提供（位于 `META-INF/jarjar/`），只用于配置界面；它沿用自己的许可证，可自行替换为其它兼容版本；LGPLv3 和 GPLv3 全文见 [`licenses/`](licenses)，jar 里在 `META-INF/licenses/`。

</details>
