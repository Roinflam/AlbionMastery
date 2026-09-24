# Albion Mastery（武器专精）

[![License: All Rights Reserved](https://img.shields.io/badge/license-All%20Rights%20Reserved-red)](LICENSE)

> Minecraft 1.20.1 · Forge · 服务端 + 客户端

阿尔比恩式武器专精系统。模组会自动识别已安装模组中的武器，玩家在战斗中积累专精等级，获得相应加成，并可选择特化方向。

## 功能

- **三层专精**：按「来源 → 类型 → 单件武器」三层累积，例如「TACZ 枪械 → 步枪 → 某把步枪」。
- **特化**：某个来源的总专精满级后，可以在两个槽位中选择特化；同一系列的特化不能同时装备。
- **自动识别武器**：自动读取已安装的枪包、刀包等内容；没有安装的来源不会出现在专精树上。
- **排行榜**：提供总榜、日榜、周榜、月榜。

## 支持的武器来源

以下模组均为可选依赖，安装后自动接入：

- TACZ（枪械）
- LR Tactical（近战）
- 拔刀剑（SlashBlade Resharped）
- Iron's Spells 'n Spellbooks
- Ars Nouveau
- Sweet Magic

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x |

## 构建

编译需要的依赖 jar 已放在 `libs/` 目录，构建脚本会自动引入。

```bash
./gradlew build
```

产物位于 `build/libs/`。部署细节见 [`README-部署说明.md`](README-部署说明.md)。

## 许可

- 本项目代码为 All Rights Reserved，详见 [LICENSE](LICENSE)。
- `libs/` 中的第三方模组 jar 仅用于编译，版权归各自作者所有。
