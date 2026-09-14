# 全局架构图生成

从仓库根目录执行，生成 README 已引用的 `doc/images/shortlink-global-architecture-leaf-hd.svg` 与同名高清 PNG。排版和字形测量由 Python 完成；SVG 文字转为真实字形轮廓，查看成品不依赖本地字体。

当前图保持蓝色跳转、绿色统计、橙色创建、紫色 Agent 四个分区。新增 Bloom 预检、主库登记与后台租约、创建发布屏障及独立 Kafka 登记提示；同名组件表示同一服务或视图，不增加部署实例。

## 依赖和生成

本次使用 Python 3、FontTools 4.55.0、Sharp 0.35.4。字形来自 Windows 微软雅黑与 Segoe UI：`msyh.ttc`、`msyhbd.ttc`、`segoeui.ttf`、`seguisb.ttf`。默认字体目录为 `C:/Windows/Fonts`，可通过 `ARCHITECTURE_FONT_DIR` 指定持有这些字体的目录；字体文件不随仓库分发。

```powershell
python -m pip install -r scripts/docs/architecture/requirements.txt
npm install --prefix scripts/docs/architecture
python scripts/docs/architecture/build.py
node scripts/docs/architecture/render.mjs --svg doc/images/shortlink-global-architecture-leaf-hd.svg --png doc/images/shortlink-global-architecture-leaf-hd.png --layout .work/architecture-bloom/layout.json --qa-dir .work/architecture-bloom/qa --overwrite
```

已有受管理的 Sharp 安装时，可以用 `ARCHITECTURE_SHARP_MODULE` 指向其模块目录；未配置时使用本目录安装的依赖。渲染只使用本地资源，不下载图标或字体。输出为 **5600 × 7600** PNG，以及可任意放大的自包含 SVG。

## 验证

生成器逐字检查字体映射，测量实际字形包围盒，检查文字、图标、节点和连线重叠及父框溢出。存在问题时返回非零退出码；修正后再导出 PNG。结构信息和检查结果位于 `.work/architecture-bloom/`。

渲染器限制输入与像素预算，检查 SVG 不引用外部图像或字体，核对实际 PNG 尺寸，并输出分区原像素裁图和 SHA256 清单。必须再查看裁图，核对箭头含义、可读性、行间距；自动几何检查不能代替视觉审查。

当前成品的检查摘要与校验值见[架构图验证记录](../../../doc/images/shortlink-global-architecture-qa.json)。文本文件校验值统一将 CRLF 转为 LF，避免 Git 换行设置影响比对；PNG 使用原始字节校验值。

本图复用已有官方品牌标识和 Phosphor 图标，未新增外部素材。`brands.py`、`generic_icons.py` 保存现有归一化 SVG 片段，Phosphor MIT 许可随脚本保留；来源、品牌归属及具体适配见[图标许可说明](../../../doc/images/architecture-icons-NOTICE.md)。
