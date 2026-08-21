# scione-scm-procurement

赛恩供应链采购管理服务，当前实现聚焦**箱唛（Shipping Mark）**：从采购订单 Excel 导入商品信息与图片，异步生成包含采购单号、SKU、商品图和 Code 128 条码的箱唛 XLSX 文件，并提供查询、预览、下载和文件访问接口。

## 功能概览

- 导入 `.xlsx` / `.xls` 采购订单，解析“产品信息”工作表中的采购单号、SKU、品名和图片。
- 支持 Excel 普通浮动图片，以及 WPS `DISPIMG` 单元格图片。
- 支持仅解析预览，或创建异步导入任务；导入成功后会生成箱唛主单和明细。
- 异步生成每条明细的 XLSX 箱唛：写入采购单号、SKU、商品图片，并生成 Code 128 条码。
- 提供主单分页查询、详情、单个标签预览/下载、批量 ZIP 下载和静态文件访问。
- 使用 MyBatis 持久化 MySQL 数据；图片和生成的标签默认保存到本地磁盘。
- 应用启动后会重新投递仍处于“处理中”的箱唛任务。

## 技术栈

| 类别 | 组件 |
| --- | --- |
| 应用框架 | Spring Boot、Spring MVC、Bean Validation、`@Async` |
| 数据访问 | MyBatis、MySQL |
| 配置与服务发现 | Nacos Config、Nacos Discovery |
| Excel 与图片 | Apache POI OOXML `5.3.0` |
| 条码 | ZXing Core `3.5.3`（Code 128） |
| API 文档 | Springdoc OpenAPI |
| 构建与测试 | Maven Wrapper、JUnit 5 / Spring Boot Test |

项目依赖企业父工程 `com.scione:scione-parent:1.0.0-SNAPSHOT`，Spring Boot、Spring Cloud 和企业组件的版本由父工程统一管理。

## 项目结构

```text
src/main/java/com/scione/scm/bill/
├── application/                 # 导入、查询、处理、下载等应用服务
├── common/                      # 业务异常与结果码
├── config/                      # MyBatis 与异步线程池配置
├── domain/shippingmark/         # 箱唛聚合、明细、状态与仓储端口
├── infrastructure/
│   ├── excel/                   # Excel / WPS DISPIMG 解析
│   ├── label/                   # XLSX 标签渲染、条码生成
│   ├── persistence/mybatis/     # MyBatis PO、Mapper 与仓储实现
│   ├── storage/                 # 本地文件存储
│   └── task/                    # 异步投递与启动恢复
└── interfaces/                  # REST Controller 与异常处理

src/main/resources/
├── application*.yml             # 默认、dev、test Profile 配置
├── mapping/                     # MyBatis XML 映射
└── templates/shipping-mark-template.xlsx  # 箱唛 XLSX 模板

doc/
├── 数据库设计.sql                # 箱唛表 DDL
└── 箱唛技术方案.md                # 方案说明
```

## 前置条件

1. JDK、Maven 版本需与企业父工程保持兼容。
2. 能访问或已安装 `scione-parent`、`scione-common` 和 `scione-api` 等企业依赖。
3. 准备 MySQL 数据库，并执行 [`doc/数据库设计.sql`](doc/数据库设计.sql)。
4. 在 Nacos 的目标命名空间中准备数据源等外部配置。仓库内没有本地 datasource 配置；`application-dev.yml`、`application-test.yml` 会导入：
   - `${spring.application.name}.yaml`
   - `${spring.application.name}-database.yaml`
5. 确保应用进程对标签文件存储目录具备读写权限。

> 不要把 Nacos、数据库或其他生产凭据写入 README、源码或提交记录；请通过环境变量、密钥管理服务或受控的配置中心注入。

## 初始化数据库

使用目标环境的 MySQL 客户端执行建表脚本：

```bash
mysql -u <username> -p <database> < doc/数据库设计.sql
```

脚本创建以下表：

| 表 | 说明 |
| --- | --- |
| `shipping_mark_import` | 箱唛导入主单，记录单号、源文件名、处理状态、操作人和处理时间 |
| `shipping_mark_detail` | 箱唛明细，记录 PO、SKU、商品图、生成状态、失败原因和标签文件地址 |

主单状态：`1` 待处理、`2` 处理中、`3` 已处理。明细状态：`1` 待处理、`2` 生成中、`3` 已生成、`4` 已失败。

## 配置

默认启用 `dev` Profile：

```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

可通过环境变量切换：

```bash
export SPRING_PROFILES_ACTIVE=test
```

箱唛本地存储目录可由 `shipping-mark.storage-path` 配置；未配置时默认为应用启动目录下的 `storage/shipping-marks`。目录布局如下：

```text
{storage-path}/
└── {billNo}/
    ├── images/{uuid}.{png|jpg|jpeg|gif}
    └── labels/{uuid}.xlsx
```

生成的文件 URL 形如：

```text
/api/v1/shipping-marks/files/{billNo}/{images|labels}/{fileName}
```

本地磁盘存储适合单机或共享卷部署。多实例、容器重建或跨节点下载场景应挂载共享存储，或实现对象存储适配器。

## 构建、测试与启动

macOS / Linux：

```bash
# 运行单元测试
./mvnw test

# 打包
./mvnw clean package

# 使用默认 dev Profile 启动
./mvnw spring-boot:run

# 使用 test Profile 启动
SPRING_PROFILES_ACTIVE=test ./mvnw spring-boot:run
```

Windows 请使用 `mvnw.cmd` 执行对应命令。

已有测试覆盖 Controller 导入响应、Excel/WPS 图片解析和标签模板图片替换。Excel 解析测试会在 `target/import-images` 写入调试图片；这是测试运行时的预期本地副作用。

启动后可访问 Springdoc（实际地址还受服务端口、上下文路径和网关配置影响）：

```text
/swagger-ui.html
/v3/api-docs
```

## Excel 导入约定

上传文件仅支持 `.xlsx`、`.xls`，且必须包含名为 **“产品信息”** 的工作表。表头可以位于工作表任意行，但必须同时包含以下列：

| 必填表头 | 用途 |
| --- | --- |
| `采购单号` | 写入标签；为空时对应明细会生成失败 |
| `SKU` | 写入标签并生成 Code 128 条码；为空或包含非 ASCII 可打印字符时会失败 |
| `品名` | 商品名称 |
| `图片` | 商品图片列；支持 WPS `DISPIMG` 或浮动图片 |

处理规则：

- 最多导入 50 条原始有效数据行；空行会跳过。
- 相同的“采购单号 + SKU”只保留首次出现的数据；采购单号或 SKU 为空的行不参与此去重。
- `图片` 是必需表头，但图片内容可为空；缺图时仍会生成保留条码的标签。
- WPS 图片解析限定 XLSX 压缩包条目数、单条和总解压大小，以避免异常压缩文件占用过多资源。
- 导入任务创建后即返回，标签生成在异步线程中执行；请通过查询接口确认明细最终状态。

## API

所有业务接口前缀为 `/api/v1/shipping-marks`。除下载与文件接口外，成功响应均由企业公共 `ApiResponse` 包装；具体 JSON 包装字段以 `scione-common` 的实现为准。

### 解析与导入

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/parse` | 仅解析上传文件，不落库、不生成标签，用于导入前预览 |
| `POST` | `/import` | 导入文件、创建主单并异步生成标签；成功返回 `202 Accepted` |

两个接口使用 `multipart/form-data`，文件字段均为 `file`。`/import` 可额外传递可选的 `operatorId` 和 `operatorName`；未传时使用系统操作人。

```bash
# 先解析预览
curl -X POST 'http://localhost:<port>/api/v1/shipping-marks/parse' \
  -F 'file=@doc/0723订单.xlsx'

# 创建异步导入任务
curl -X POST 'http://localhost:<port>/api/v1/shipping-marks/import' \
  -F 'file=@doc/0723订单.xlsx' \
  -F 'operatorId=demo' \
  -F 'operatorName=演示用户'
```

导入成功的数据对象包含 `id`（主单 ID）、`billNo`、`billName` 和 `detailCount`。`/import` 对部分业务校验错误会返回 HTTP `200` 的失败业务响应，因此调用方应同时检查 HTTP 状态和响应业务码。

### 查询

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/` | 主单分页查询 |
| `GET` | `/{markId}` | 查询主单及其全部明细 |

分页查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `bill_no` | 否 | 单据编号，精确匹配 |
| `bill_name` | 否 | 上传文件名，模糊匹配 |
| `status` | 否 | 逗号分隔的状态；可传 `1,2,3` 或 `待处理,处理中,已处理,已完成` |
| `pageNum` | 否 | 页码，默认 `1`，最小 `1` |
| `pageSize` | 否 | 每页数量，默认 `10`，范围 `1`–`100` |

```bash
curl 'http://localhost:<port>/api/v1/shipping-marks?pageNum=1&pageSize=10&status=处理中,已处理'
curl 'http://localhost:<port>/api/v1/shipping-marks/<markId>'
```

详情中的每条明细会包含 PO、SKU、品名、商品图 URL、生成状态、失败原因及标签文件 URL。

### 预览与下载

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/{detailId}/preview` | 返回已生成明细的 PO、SKU、商品图及 Code 128 条码 Data URI |
| `GET` | `/{detailId}/download` | 下载单个已生成箱唛 XLSX |
| `POST` | `/download` | 批量下载已生成箱唛，返回 ZIP |
| `GET` | `/files/{billNo}/{category}/{fileName}` | 读取已保存的图片或标签文件 |

```bash
# 预览 / 下载单个标签
curl 'http://localhost:<port>/api/v1/shipping-marks/<detailId>/preview'
curl -OJ 'http://localhost:<port>/api/v1/shipping-marks/<detailId>/download'

# 批量下载；字段名兼容历史约定，但数组元素实际是 detailId
curl -X POST 'http://localhost:<port>/api/v1/shipping-marks/download' \
  -H 'Content-Type: application/json' \
  -d '{"markIds":[101,102]}' \
  -o shipping-marks.zip
```

注意：预览、单个下载和批量下载使用的均是**明细 ID (`detailId`)**，而不是主单 ID。批量请求的字段名为历史兼容的 `markIds`，每次最多 50 个 ID；待打包源文件总大小超过 50 MiB 时会被拒绝。只有状态为“已生成”且存在标签文件的明细可以预览或下载。

`category` 仅允许 `images` 或 `labels`，文件名只允许受控的 UUID 文件名和图片/XLSX 扩展名。

## 处理流程与失败排查

```text
上传 Excel
  ├─ POST /parse：解析预览，不保存
  └─ POST /import：解析 → 保存商品图 → 保存主单/明细 → 异步投递
                                                   ↓
                              校验 PO / SKU → 渲染 XLSX 模板 → 保存标签文件
                                                   ↓
                               查询主单和明细状态 → 预览或下载已生成标签
```

常见明细失败原因：

| 现象 | 处理建议 |
| --- | --- |
| `采购单号为空` | 补全 Excel 中的采购单号 |
| `SKU为空` | 补全 SKU |
| `SKU条码未生成` | SKU 仅使用 ASCII 可打印字符，避免中文或其他特殊 Unicode 字符 |
| `箱唛生成失败` | 检查 XLSX 模板、商品图片可读性、标签目录权限和应用日志 |

主单“已处理”表示所有明细已结束处理，其中可能包含失败明细；不能将其等同于“全部标签生成成功”。请以明细状态和 `errorReason` 为准。

标签渲染依赖 [`src/main/resources/templates/shipping-mark-template.xlsx`](src/main/resources/templates/shipping-mark-template.xlsx) 中约定的 WPS 图片标识与媒体路径。替换模板时必须同步验证渲染逻辑和输出结果。

## 运行注意事项

- 该仓库未包含数据库迁移工具、生产 Profile 和本地数据源配置；部署前需在目标 Nacos 命名空间配置数据源及必要的企业环境参数。
- 当前本地存储实现不提供跨节点共享、对象存储、定时重试或分布式任务锁。多实例部署时应评估共享存储、重复投递和故障恢复策略。
- 应用启动时仅恢复状态为“处理中”的历史主单；异常退出后的任务恢复依赖该机制。
- Controller 中未见鉴权注解。接口是否受到保护取决于网关或全局安全配置；生产环境应明确认证、授权和文件访问策略。
- `doc/箱唛技术方案.md` 中的早期接口描述与实际 Controller 存在演进差异（例如实际存在 `/parse`，而标签预览/下载使用明细 ID）。接入方应以本文和 `ShippingMarkController` 为准。
