# EnGlobalMarket

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/eta000Propofol/EnGlobalMarket?sort=semver)](https://github.com/eta000Propofol/EnGlobalMarket/releases/latest)
[![Build](https://github.com/eta000Propofol/EnGlobalMarket/actions/workflows/build.yml/badge.svg)](https://github.com/eta000Propofol/EnGlobalMarket/actions/workflows/build.yml)
[![Server](https://img.shields.io/badge/Paper-26.x-8A2BE2)](https://papermc.io/downloads)
[![Java](https://img.shields.io/badge/Java-25%2B-orange)]()

> 适用于 Paper 26.x（26.1.2 起，Java 25+）的轻量级全球市场插件：箱子 GUI 上架/购买商品，Vault 货币结算。

## 简介

玩家通过箱子 GUI 浏览、上架、购买商品，交易使用 Vault 货币结算。支持成交税率、
按物品自身堆叠上限限制上架数量、未售出自动下架、个人收货箱，数据存于 SQLite。

## 功能特性

- 箱子 GUI 浏览商品，市场 / 我的商品 / 收货箱均为每页 36 件、支持翻页
- 商品图标上显示卖家、整组总价、单价、上架时间与剩余有效期
- 上架数量取物品自身的原版堆叠上限（普通物品 64，雪球/鸡蛋/末影珍珠等 16，
  药水/工具/装备 1），一次最多一个堆叠
- 成交税率可配，税从卖家所得中扣除；买家支付挂牌总价
- 未售出超过有效期自动下架并转入卖家收货箱，卖家在线时收到提醒
- 个人收货箱：上线自动投递（单次最多 100 件），也可在「我的商品」里手动领取
- 数据存于 SQLite（WAL 模式），驱动已打入 jar，无需额外安装数据库

## 环境依赖

| 依赖 | 说明 |
| --- | --- |
| [Paper 26.x](https://papermc.io/downloads) | 服务端，26.1.2 / 26.2 / 26.3 均可（需 Java 25+）。插件按 26.1.2 API 编译、`api-version` 为 26.1，1.21.x 及更低版本会拒绝加载 |
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | 经济接口，需要已注册的经济实现（如 EssentialsX / CMI 等） |

> 未安装 Vault（或 Vault 没有可用经济实现）时插件仍会加载，但所有交易操作都会
> 提示「服务器未接入经济插件」并拒绝执行。

## 安装

1. 服务器安装 Paper 26.x（26.1.2 或更高，需 Java 25+）。
2. 安装 Vault 以及任意经济插件（如 EssentialsX / CMI / 其他支持 Vault 的经济插件）。
3. 把 `build/libs/EnGlobalMarket-<版本>.jar` 放入服务器的 `plugins` 文件夹。
4. 启动服务器，插件会自动生成 `plugins/EnGlobalMarket/config.yml` 和 `market.db`。
5. 修改配置后执行 `/market reload` 重载（重读 config.yml、修正税率并重新计算过期检查间隔）。

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/market`、`/gm`、`/globalmarket` | 打开全球市场 | `englobalmarket.use`（默认所有人可用） |
| `/market reload` | 重载配置 | `englobalmarket.admin`（默认 op） |

`/market reload` 支持 Tab 补全（仅对拥有 `englobalmarket.admin` 的玩家显示）。

## 使用说明

- **浏览**：`/market` 打开 45 格市场，0–35 为商品格（按上架时间倒序，每页 36 件），
  底部为 上一页 / 我的商品 / 创建商店 / 退出 / 下一页。
- **购买**：点他人商品 → 确认界面 → 整组成交，物品直接进背包（放不下则掉在脚下）；
  点自己的商品则直接进入该商品的管理界面。
- **上架**：创建商店 → 物品放入第一格 → 点绿宝石 → 聊天栏输入整组总价
  （60 秒内有效，`cancel` 取消，上限 10 亿）→ 点「确认上架」。
- **管理**：改价（同样在聊天栏输入）或下架；下架物品直接回背包，放不下才进收货箱；
  过期商品自动进收货箱，上线时自动投递，也可在「我的商品」里手动领取。

## 配置

税率、货币名、商品有效期 / 上架上限 / 检查间隔、界面标题、全部提示文案的说明与取值范围，
都写在 `plugins/EnGlobalMarket/config.yml` 的注释里。改完执行 `/market reload` 生效。

## 数据存储

商品与收货箱数据保存在 SQLite（`plugins/EnGlobalMarket/market.db`，WAL 模式），
表 `listings` 与 `claims`；物品以 `ItemStack` 序列化字节完整保存（含附魔、NBT、自定义物品）。
SQLite 驱动已打入 jar，无需额外安装数据库。

界面展示的商品图标是副本，会临时清空 `PersistentDataContainer`，避免其他插件
（如 EzStrengthen）注册的「打开容器即按 PDC 重建 lore」监听覆盖市场信息行；
存储与成交仍使用带完整 PDC 的原物品。

> 注意：sqlite-jdbc 的 JNI 原生库按原包名 `org.sqlite.core.NativeDB` 查找类，
> 因此该驱动未做包重定位，请勿与其他同样内置 sqlite-jdbc 的插件同时使用。

## 构建

需要 JDK 25+；首次构建会联网下载依赖。

```powershell
# Windows
gradlew.bat build
```

```bash
# Linux/macOS
./gradlew build
```

产物为 `build/libs/EnGlobalMarket-<版本>.jar`（shadowJar，已含 SQLite 驱动，部署用这个）；
同目录的 `EnGlobalMarket-<版本>-thin.jar` 是不含依赖的精简包，不要拿去开服。
单独跑单元测试（价格解析/税率计算与钳制/分页/过期计算/剩余时间显示，含边界与随机不变量）：

```bash
./gradlew test
```

## License

本项目基于 [Apache-2.0](LICENSE) 协议开源。
