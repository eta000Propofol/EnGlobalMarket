# EnGlobalMarket

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/eta000Propofol/EnGlobalMarket?sort=semver)](https://github.com/eta000Propofol/EnGlobalMarket/releases/latest)
[![Server](https://img.shields.io/badge/Paper-26.1.2-8A2BE2)](https://papermc.io/downloads)
[![Java](https://img.shields.io/badge/Java-25%2B-orange)]()

> 适用于 Paper 26.1.2（Java 25+）的轻量级全球市场插件：箱子 GUI 上架/购买商品，Vault 货币结算。

## 简介

通过箱子 GUI 上架/购买商品，交易使用 Vault 货币结算，支持成交税率、
原版堆叠上限、未售出自动下架、收货箱。

## 功能特性

- 箱子 GUI 浏览/搜索商品，每页 36 件，支持翻页
- 上架数量按原版堆叠上限限制（普通物品 64、雪球/鸡蛋 16、药水/装备 1）
- 成交税率，税从卖家所得中扣除
- 未售出自动下架退回，物品进入个人收货箱
- 数据存于 SQLite，驱动已打入 jar，无需额外安装数据库

## 环境依赖

| 依赖 | 说明 |
| --- | --- |
| [Paper 26.1.2](https://papermc.io/downloads) | 服务端（需 Java 25+） |
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | 经济接口，需要已注册的经济实现（如 EssentialsX / CMI 等） |

## 安装

1. 服务器安装 Paper 26.1.2（需 Java 25+）。
2. 安装 Vault 以及任意经济插件（如 EssentialsX / CMI / 其他支持 Vault 的经济插件）。
3. 把 `build/libs/EnGlobalMarket-<版本>.jar` 放入服务器的 `plugins` 文件夹。
4. 启动服务器，插件会自动生成 `plugins/EnGlobalMarket/config.yml` 和 `market.db`。
5. 修改配置后执行 `/market reload` 重载。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/market`、`/gm`、`/globalmarket` | 打开全球市场 | `englobalmarket.use`（默认所有人可用） |
| `/market reload` | 重载配置 | `englobalmarket.admin`（默认 op） |

## 使用说明

- 市场界面每页展示 36 件商品（每件占 1 格，支持翻页），底部按钮：
  上一页 / 管理我的商品 / 创建商店 / 退出 / 下一页。
- 点击他人商品 → 确认购买界面，确认后整组成交，不能购买自己的商品。
- 创建商店：把物品放入第一格 → 点击绿宝石用铁砧输入整组总价 → 点击确认上架。
- 管理我的商品：查看/修改价格/下架自己的商品；下架或过期物品进入个人收货箱，
  背包放得下时上线自动投递，也可以打开「收货箱」手动领取。

## 规则与配置（plugins/EnGlobalMarket/config.yml）

- `economy.tax-rate`：成交税率（小数），默认 `0.05`（5%），税从卖家所得中扣除，
  买家支付挂牌价。卖家实收 = 售价 × (1 − 税率)，向下取整到 2 位小数。
- `economy.currency-name`：货币显示名称。
- `market.expire-days`：未售出多少天后自动下架退回，默认 `7`。
- `market.max-listings-per-player`：每名玩家同时上架上限，默认 `20`。
- `market.check-interval-minutes`：过期检查间隔，默认 `5` 分钟（启服时立即检查一次）。
- `gui.*`：各界面标题；`messages.*`：提示消息（支持 `&` 颜色代码）。

## 数据存储

商品与收货箱数据保存在 SQLite（`plugins/EnGlobalMarket/market.db`），
物品以序列化字节完整保存（含附魔、NBT、自定义物品）。SQLite 驱动已打入 jar，
无需额外安装数据库。

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

产物位于 `build/libs/EnGlobalMarket-<版本>.jar`（含 SQLite 驱动的单个 jar）。

## License

本项目基于 [Apache-2.0](LICENSE) 协议开源。
