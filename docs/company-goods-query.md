# 企业货品查询

## 本次范围

- 插入两条示例货品：`DEMO-GOODS-SET-001` 和 `DEMO-GOODS-ITEM-001`，前者关联后者，比例为 6。
- 提供货品列表、详情两个只读接口；不执行外部同步。
- 前端入口：`/supply-chain/company-goods`，位于“基础信息 / 企业货品库”。
- 详情抽屉使用共用的 `GoodsFields` 组件，支持只读参数和字段变更回调。本次不开放编辑保存、导入、导出。

示例 SQL：`sql/company_goods_examples.sql`。脚本仅补入不存在的示例 SKU，不修改已有记录；实际开发库已执行一次。由于 SKU 只有普通索引，此脚本不用于并发执行。

另补充独立普通产品 `DEMO-GOODS-SINGLE-001`（示例礼品包装盒），无子 SKU，采购成本为 10.00 元。补充脚本为 `sql/company_goods_ordinary_example.sql`。

## 列表接口

`GET /company-goods/list`

| 参数 | 含义 |
| --- | --- |
| sku | 主、子 SKU 模糊匹配，忽略大小写 |
| goods_name | 主、子货品名称模糊匹配 |
| personnel_name | 开发人、采购员、产品负责人任一模糊匹配 |
| product_attribute | 0 自制、1 外购、2 委外，可命中主或子货品 |
| product_type | 主货品类型：0 普通、1 组合 |
| goods_status | 展示状态：0 待创、1 已创、2 异常 |
| product_category | 分类标识精确匹配，可命中主或子货品 |
| page | 页码，默认 1，最小 1 |
| pageSIze | 页容量，默认 10，范围 1～100；保留设计文档拼写 |

不同条件 AND，人员三个角色 OR；不同条件允许分别命中同一主货品下的不同子项。输入中的 `%`、`_` 按普通字符匹配。

被其他货品 `group_list` 引用的 SKU 不重复进入主货品分页。子货品命中后返回整个主货品和其一层子项。主货品或任一子货品的 `conflict_status=1`、`exception_status=1` 时展示为异常；待创、已创筛选排除这些异常项。该展示规则不修改数据库中的创建状态。

响应沿用项目 `ApiResponse<PageResult<CompanyGoodsListItemVO>>`：成功 `code=0`，分页位于 `data`，记录位于 `data.records`；主货品及子项字段按文档使用下划线，分页结构沿用项目驼峰属性。

排序固定为 `updated_time DESC, id DESC`。子货品顺序沿用存储关联顺序。子货品的 `quantity` 取当前主 SKU 存储的 `group_list` 中对应关系的 `quantity`，用于列表及详情/编辑表单只读展示；同一子 SKU 在不同主 SKU 下分别取各自关联数量。主货品没有该关联数量，返回 null，页面显示“—”。

## 详情接口

`GET /company-goods/detail?id=1`

按正整数主键查询任意货品，返回完整主表字段，JSON 字段解析为数组。`group_list` 返回一层完整子货品，子货品的 `group_list` 返回空数组，不递归展开，也不修改数据库中原始关联。

报关单价 `customs_import_price`、默认清关单价 `clearance_price` 的数据单位为 USD，与原型及已调整的数据库定义一致；接口原样返回数值，不进行汇率换算。

- `id`、`version` 按字符串传输。
- 日期格式为 `yyyy-MM-dd HH:mm:ss`；数据库允许为空的业务字段保留 null。
- 主图取 `picture_list` 中 `is_primary=1` 的有效图片；没有主图标记时取第一条有效图片，无图返回 null。S3 图片保存 `object_key`，查询时通过 `FileApplicationService.presignedUrl` 获取临时 `image_url`；详情另返回 `image_object_key`，供未更换图片时保存。历史 `pic_url` 链接或 Base64 继续回显。
- 缺少或非法参数返回 400，货品不存在返回 404。
- 关联子货品缺失、同 SKU 多条关联记录无法确定目标、JSON 格式错误时明确报错，不伪造空结果。

## 编辑接口

`POST /company-goods/update` 接收 `CompanyGoodsUpdateDTO`，主货品可编辑字段直接放在请求顶层，`group_list` 为**现有子 SKU 的完整编辑表单**，不接收关联比例或新增关联。普通产品传空数组。

- 每个 SKU 必传 `id`、读取详情时的 `version`；二者支持整数字符串。整数编码、数量拒绝小数，避免反序列化时截断。
- 主、子 SKU 一次提交，整体成功或回滚；子项不得遗漏、重复或替换成其他货品。子 SKU 必须通过所属主货品编辑。
- 编辑字段采用完整表单语义：可选项传 `null` 或空数组表示清空；系统字段不在 DTO 和 UPDATE 白名单中。SKU、ASIN、产品 ID、产品类型、来源、关系及比例、创建状态、异常标记、供应商报价、清关国家均不因本次编辑改变。子 SKU 同步范围只读。
- 按原型校验必填、单位、枚举分类、正数/正整数、规格三项完整性、最多两位小数、HSCODE 数字文本、负责人格式及名称唯一。图片沿用原型的 5MB 原图限制及前端压缩；新图片以压缩 data URI 随保存请求提交，后端转换为 MultipartFile，复用 `FileApplicationService.upload` → `S3Client.upload`，存入 `company-goods/{id}` 目录；`picture_list` 仅保存返回的 `object_key` 和主图标记，不保存新图 Base64 或临时签名 URL。接口拒绝 SVG 和任意新对象键；未更换图片时提交原对象键，保留原始 `picture_list`。
- 缺少数据源的人员、工艺路线、仓库、质检模板等允许为空，不伪造外部字典；产品分类必须是本地维护枚举中的名称。子 SKU 数量只读展示主 SKU 的 group_list.quantity；主 SKU 数量显示“—”，编辑保存不改变关联数量。
- 每个保存记录执行版本比较并递增 `version`，更新 `updated_time`；旧版本返回 HTTP 409。名称查重在 REPEATABLE_READ 事务中执行锁定读取，当前没有名称唯一索引，大表场景需评估名称索引及锁范围。
- 本次仅本地保存，成功提示“货品已保存”，不登记或执行领星/黑湖同步，不更改创建状态和异常标记。

执行步骤：`CompanyGoodsController.update` 校验 DTO → `CompanyGoodsService.update` 核对现有主子关系、版本、业务字段和重名 → `CompanyGoodsMapper.update` 白名单更新每个 SKU。前端列表“编辑”与“详情”共用 `GoodsDetailDrawer` 和 `GoodsFields`，覆盖五组字段、图片更换/移除、跨 SKU 校验定位、未保存关闭确认及保存后列表刷新。

本次新增验证：`CompanyGoodsUpdateTest` 覆盖业务校验、子 SKU 越界、版本冲突、图片和整数反序列化；`CompanyGoodsMapperIntegrationTest` 增加真实数据库 UPDATE、MockMvc HTTP 保存/400/409、Spring 事务回滚验证。所有数据库测试数据均回滚。

2026-10-23 编辑功能自测：后端共 29 项测试通过，含 7 项真实 MySQL 集成测试；前端表单 21 项断言通过，TypeScript 和相关文件 ESLint 通过。浏览器使用实际页面验证普通/组合货品编辑、主子切换、分类下拉、必填校验和整数补齐 `.00`，未向当前旧版服务提交有效编辑。当前 9501 进程需重启后才能在浏览器使用新增保存接口，未自动重启用户进程。

### S3 图片生命周期

选图、更换、移除仅修改本地草稿。`CompanyGoodsService.update` 完成所有 SKU 的校验后才执行上传。数据库回滚时清理本次成功上传的新对象；数据库提交后再通过 `S3Client.delete` 删除被更换、移除的旧对象。未更换图片不重复上传或删除，签名 URL 过期不会导致对象标识改变。

历史图片没有 `object_key` 时，不推测对象键并删除外部文件。提交后旧图删除失败会明确返回“货品已保存，但旧图片删除失败”，并记录需要人工清理的对象键；回滚后的新图清理失败记录错误，保留原保存失败原因。S3 与数据库不是同一个原子事务，清理服务异常时需要按错误日志处理，当前未新增异步重试任务。

2026-10-25 S3 改造自测：后端 34 项测试通过，含 7 项真实 MySQL 集成测试；模拟 S3Client 验证 multipart 上传、预签名地址、提交后删除旧图、回滚清理新图及存储失败提示。前端表单 25 项断言、TypeScript、相关文件 ESLint 通过。未调用真实 S3 环境进行上传或删除，未重启现有应用进程。

## 查询主业务入口

分类接口 `GET /company-goods/blacklake/product-categories` 读取本地 `BlacklakeProductCategoryEnum`，统一响应的 `data` 为按编码 0～17 排列的18个分类名称字符串，不再返回黑湖元数据。远端产品分类元数据查询方法已移除。

1. `CompanyGoodsController` 映射查询参数并校验，调用 `CompanyGoodsService`。
2. 列表先查询总数和当页主货品，再合并整页子 SKU 执行一次批量查询，组装列表 VO。
3. 详情按主键读取，再批量读取一层子货品，组装详情 VO。
4. 前端通过 `lib/api/company-goods.ts` 复用 `scmHttp` 请求；列表与详情有独立的加载、错误、重试处理。

## 验证

2026-10-23 已执行：

- `CompanyGoodsServiceTest`：6 项通过。覆盖批量关联、一层循环截断、大整数和空字段、主图选择、缺失及重复子 SKU、非法 JSON、通配符转义。
- `CompanyGoodsMapperIntegrationTest`：4 项真实 MySQL 测试通过。覆盖主 SKU 分页、跨子项组合筛选、异常聚合、主类型筛选及人员匹配。测试数据在事务中回滚，不保留测试货品。
- 对独立查询进程执行 30 项 HTTP 检查通过，涵盖实际示例数据、子 SKU 查询、空结果、翻页、非法参数和 404。
- 前端 `npx tsc --noEmit --incremental false` 通过；新增页面、组件、API 文件的 ESLint 检查通过。
- 浏览器独立组件预览调用真实查询接口，验证列表展开、子 SKU 详情定位、主子切换、五组字段、只读展示、筛选与空态、重置。原应用登录验证未修改；当前浏览器未登录，未验证原应用登录后的完整访问链路。

单元测试命令：

```powershell
mvn '-Dtest=CompanyGoodsServiceTest' test
```

真实数据库测试需要提供包含 `spring.datasource.url/username/password` 的外部 YAML 配置：

```powershell
mvn '-Dtest=CompanyGoodsServiceTest,CompanyGoodsMapperIntegrationTest' '-Dgoods.integration.config=<外部配置绝对路径>' test
```

未执行生产部署、完整生产构建及大数据量性能压测。现有后端运行进程需要重启以加载新增接口。独立自测进程不启动合同定时任务、不注册服务发现。

## 批量导入（2026-10-25）

入口：企业货品库右上角“批量导入”，打开右侧抽屉。下载最新模板，填写并上传，点击“校验并导入”；结果显示原文件行号、SKU和失败原因。失败记录可下载为Excel，修改后直接重传。

- `POST /company-goods/import`：multipart/form-data，字段名 `file`，仅 `.xlsx`，最大5MB、最多2000条非空记录。使用最新版44列表头；底部模板说明行不作为货品。公式及错误单元格不接受。
- `GET /company-goods/import/template`：返回随应用打包的最新模板，原件为工作区 `docs/货品信息导入导出模板.xlsx`。
- 响应为统一ApiResponse，data包含 `successCount`、`failureCount`、`records`（rowNumber/sku/success/message）及 `failedFileBase64`。失败文件保留原模板列，追加原文件行号、失败原因；重传时忽略这两列。
- 主流程：CompanyGoodsController.importFile → CompanyGoodsImportService.importFile → CompanyGoodsExcelParser.parse；解析文件后通过LingxingSupplierClient.findAllSuppliers拉取完整供应商列表，进行供应商名称、文件内重复和关联校验，再按普通货品、关联主货品顺序逐行独立事务保存，最后返回结果与失败文件。
- 每行填写当前SKU字段。企业商品ID对应product_id；子SKU仅写入group_list中的sku；忽略子SKU名称。空白或 `/` 表示无子SKU，新增判为普通产品；填写一个子SKU则判为组合产品。暂不支持多个子SKU、多层或循环关联。关联货品须已存在或在本文件成功导入；本文件中的子货品失败时，其主货品也失败。
- 单位默认“个”，外部创建时组合产品子SKU比例取数据库group_list中的quantity。新增来源为企业商品库，创建方式0导入，状态1已创，异常标记0，版本0。货品与每个目标系统的本地消息同事务保存，提交后由Kafka消费者异步创建；详见[导入外部创建流程](company-goods-external-create.md)。
- 导入仅支持新增。SKU已存在时该行失败，提示通过编辑功能修改；不覆盖已有记录。新主货品仍可关联数据库中已有的普通子货品，无需重复导入子货品。
- 每次有效文件导入实时查询一轮领星供应商列表：`POST /erp/sc/data/local_inventory/supplier`，`length=1000`，从`offset=0`开始，按本页实际数量推进offset，依据total判断是否还有下一页；不跨导入缓存。复用现有领星客户端鉴权、签名、请求排队及限流处理，仅在内存保留supplier_id和supplier_name，不落库供应商字典。
- 供应商名称去除首尾空格后完整匹配，不做模糊匹配，也不额外按启停或删除状态过滤。名称不存在则该行失败并写入失败文件；关联的本次子货品失败时主货品同样失败。任一页查询失败或响应数据不完整时，整次导入在写库前终止，不使用部分列表校验。其余外部字典仍暂填名称。
- 工艺路线查询复用BlacklakeOpenApiClient的Redis Token、登录会话和异常处理：每次有效文件导入调用一次`POST /api/dytin/external/processRouting/queryList2`，请求为`{"page":{"pageNum":1,"pageSize":100}}`，只读取第一页，不按total翻页、不缓存结果。按文档解析`data.data`数组，内存仅保留processRoutingCode/processRoutingName。工艺路线选填，非空时去除首尾空格后完整匹配processRoutingName，不接受以编码代替名称；不在第一页结果中的行失败，并在结果及失败文件中显示原因。远端、Redis、认证、响应结构异常在数据库写入前终止整次导入，不静默跳过校验。
- 产品分类使用现有枚举；同步范围、产品属性、是否关键部位、质检方式按现有编码转换；负责人英文逗号分隔，检验类型转JSON名称数组。其他外部字典暂保存名称。按模板星号校验必填；单品毛重选填，数值最多两位小数，单箱数量正整数；外箱/包装尺寸若填写需完整。
- 检验类型通过GoodsInspectionTypeEnum校验：int编码1来料检验、2成品入库检验、3销售发货检验、4其他检验。Excel仍填写名称，单个或多个均可，多个以英文逗号分隔；未知名称、编码数字、重复选项、空项及错误分隔符使该行失败。字段仍选填，存储沿用JSON名称数组，失败原因随导入结果及失败文件返回。
- SKU、名称冲突使用RR事务中的锁定读；表暂无唯一约束，冲突查询涉及无索引名称扫描，锁范围可能较大。单次最多2000行，逐行提交，不执行自动重试。
- 本次前端未复用原型CSV文案；文件格式、必填、分隔符和字段均以用户确认的Excel模板为准。原型的抽屉步骤、部分成功提示、结果表与失败文件下载交互保留。

### 导入验证结果

- 工艺路线校验本次执行35项黑湖客户端、Redis装配、导入服务、解析及Controller测试，以及1项真实MySQL导入事务测试，均通过；前端导入组件ESLint通过。覆盖固定第一页100条、仅保留编码名称、名称完整匹配、空值选填、空列表、关联失败及远程失败前无数据库操作。黑湖使用本地HTTP模拟服务；真实环境未找到缓存Token，因此未登录或调用真实黑湖工艺路线接口。
- 供应商校验本次执行25项客户端、导入服务、Excel解析及Controller测试和2项真实MySQL导入相关测试，均通过；前端导入组件ESLint通过。领星响应使用本地HTTP测试服务模拟，覆盖1001条分页、整千条结束、每次重新拉取、空列表、后续页失败、无效响应及供应商不存在时关联失败。未调用真实领星供应商接口；数据库测试使用UUID前缀数据并回滚或清理。
- 后端累计48项测试通过（新增14项）；解析、服务、Controller及真实MySQL映射均已覆盖。包括先主后子文件顺序、名称/SKU重复、非法精度、缺子SKU、循环/多层关联、失败文件可重传、缺文件400，以及有效两行提交而另两行失败的真实事务验证。
- 真实数据库导入测试仅使用UUID前缀记录，验证结束后清理；其他映射测试回滚，不修改现有示例货品。
- 前端TypeScript、相关文件ESLint通过；浏览器已验证右上角按钮、抽屉布局、说明和未选文件时提交禁用，截图保存在工作区 `docs/企业货品库自测/货品批量导入页面.png`。
- 运行中的用户后端未重启；完整浏览器上传至该进程的联调尚未执行。multipart请求至Controller、实际服务和MySQL的联调通过MockMvc完成。

## 勾选与导出（2026-10-25）

- 列表最左侧仅主SKU提供复选框；子SKU该列留空。表头全选仅影响本页，支持半选；翻页、筛选、切换产品类型保留选择，可点击“清空选择”取消全部。
- 查询区增加“导出”，未勾选时提示“请先选择货品”。导出中禁用选择及重复提交，成功下载Excel，失败时读取Blob中的统一错误提示。
- `POST /company-goods/export`，JSON请求 `{ "ids": ["1", "35"] }`，ID按字符串传输，后端严格解析Long，避免大整数精度损失。只接受勾选的主货品，失效或已成为子项的选择明确报错。
- 主入口：CompanyGoodsController.export → CompanyGoodsExportService.export → CompanyGoodsMapper.findExportParents；批量读取主项和一层子项，在同一个RR只读事务中生成文件。不会读取S3或调用外部系统。
- 导出为当前44列Excel模板；勾选主货品与其关联子货品独立成行，共用子货品只输出一次。子SKU列写入关系编码，子SKU名称展示实际子货品名称；没有关系的两列留空。当前模板暂不支持多个子SKU及多层关联，遇到这类数据明确拒绝，避免静默漏导。
- 单次最多选择2000条主货品，主子SKU合计最多2000条。文件采用模板中的名称枚举和英文逗号多值，金额/小数格式为0.00；SKU、产品ID、HSCODE按文本保存，超15位有效数字的小数以文本保留原值，防止Excel精度截断。未知编码或损坏关联明确报错。
- 使用当前44列模板，因此开发人、单位、数量、图片等模板未包含的字段不导出。导入时仍按既定规则保留已有开发人、单位、图片和关联数量。
- 新增8项验证，后端共56项测试通过，包含真实MySQL主货品筛选、子货品读取、HTTP下载响应、导出文件重新解析、大ID/金额精度和非法关系校验。前端TypeScript及相关ESLint通过。
- 浏览器验证了未选择提示、单选/全选/半选、展开子SKU无选择框，以及切换筛选后保留选择。对运行中的 `POST /company-goods/export` 实际请求返回HTTP200和xlsx类型，下载文件核对为44列表头、正确SKU及采购成本10.00；没有执行数据库写入。截图为工作区 `docs/企业货品库自测/货品勾选导出页面.png`。

### 采购字段展示补充

详情/编辑共用字段配置在供应商后补充供应商报价（supplier_quote），单位元、领星不含税单价，整数补齐.00；详情只读展示，编辑页可修改并保存主、子SKU报价；校验非负、最多两位小数，数据库更新supplier_quote。列表子SKU编码前移除↳符号，并增加与主SKU展开按钮等宽的左侧留白，使编码文字对齐。前端TypeScript、相关ESLint及浏览器详情字段顺序/值验证通过。

供应商报价编辑验证：15项编辑单元测试、1项真实数据库SQL更新测试通过（事务回滚）；前端30项表单断言、TypeScript及相关ESLint通过。未在现有业务货品上执行保存。

- 关键部位使用BlacklakeKeyPartEnum：int编码0否、1是，Excel仅填写“否”或“是”，允许留空。领星默认质检方式使用LingxingQualityInspectionMethodEnum：int编码1抽检、2全检、3免检，Excel必填对应名称。数字编码、未知名称和多个选项均使该行失败，原因随导入结果及失败文件返回。

- 当前临时暂停领星供应商、黑湖工艺路线的系统存在性校验及导入前远程列表查询，相关实现以注释保留。供应商仍必填且仅支持一个名称，工艺路线仍选填，其他导入规则保持生效。

- 导入新增现已接入Kafka外部创建；页面导入成功代表本地保存及同步任务入库，不代表外部已创建。查询字典及名称编码转换在消费者中执行；失败详情记录在goods_local_message.last_error_message，并设置company_goods.exception_status=1。
