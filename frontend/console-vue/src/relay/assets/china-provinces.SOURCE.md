# 中国省级地图资源来源

本目录的 `china-provinces.json` 是 Apache ECharts **4.9.0** 历史发布数据的派生 SVG 路径资源，生成于 2026-09-15。它用于访问统计省级填色，不表示实时测绘或最新行政边界服务。渲染不依赖第三方请求，也不新增图表运行时依赖。

## 固定版本与来源

上游仓库：<https://github.com/apache/echarts>。tag `4.9.0` 固定 commit 为 `90243fca100866ea802249a98df8b0899e68927e`；通过 `git ls-remote` 核对。所有下载同时校验 SHA-256，避免 tag 或资源内容意外变化。

| 用途 | 精确上游资源 | SHA-256 |
| --- | --- | --- |
| 34 个省级多边形 | [map/json/china.json](https://raw.githubusercontent.com/apache/echarts/90243fca100866ea802249a98df8b0899e68927e/map/json/china.json) | `d392f651a48e6213c9bfc83f406711069de296c17f426cffc0ad1148078ee226` |
| 南海诸岛附图 | [src/coord/geo/fix/nanhai.js](https://raw.githubusercontent.com/apache/echarts/90243fca100866ea802249a98df8b0899e68927e/src/coord/geo/fix/nanhai.js) | `52bee92975de684bd748ed7271a2d550eb6d74d76e7c7415b2ce6d39a34be0e2` |
| UTF8 坐标解码算法参照 | [src/coord/geo/parseGeoJson.js](https://raw.githubusercontent.com/apache/echarts/90243fca100866ea802249a98df8b0899e68927e/src/coord/geo/parseGeoJson.js) | `a8b267b13ecc504d884943a639a70edcf0150378f82c91c24ef40c066e0a0381` |
| 上游授权 | [LICENSE](https://raw.githubusercontent.com/apache/echarts/90243fca100866ea802249a98df8b0899e68927e/LICENSE) | `512b2001a3a2ddd456a08d1392d5831da992daaf18b10e76f9b4a5b4ae7fe96c` |
| 上游版权声明 | [NOTICE](https://raw.githubusercontent.com/apache/echarts/90243fca100866ea802249a98df8b0899e68927e/NOTICE) | `c40dc495b6220f2006119ea08218a4c2b15bd113a06af071950edaf34037626e` |

当前 ECharts 官网[已不再提供地图下载](https://echarts.apache.org/en/download-map.html)。此处固定使用其历史仓库文件；没有把 ECharts 的现行包错误当成提供中国边界的数据服务。

## 变换和结构

**修改声明**：ShortLink 对原文件执行确定性的 UTF8 解码和 SVG 投影，补充中文全名与资源溯源字段；没有手绘边界，没有删除顶点，没有简化多边形。

- 省级输入含 34 个 Feature，保留上游 6 位行政区划 `id` 为 `code`，上游中文 `name` 为 `shortName`。另加 `name` 全称用于展示；唯一键始终使用代码。
- 解码与上游 `decodePolygon` 相同：每两个 UTF-16 code unit 去除 64 偏移，ZigZag 解码、累加 delta，除以默认 `UTF8Scale=1024`。
- 所有 Polygon / MultiPolygon 的外环、内环均保留：**177 个环、16,173 个顶点**。多个环串为一个 SVG path，渲染必须设置 `fill-rule="evenodd"` 才能保留孔洞。
- 南海附图来自同版本 `nanhai.js` 的原始 12 个环；按原始公式 `x / 10.5 + 126`、`y / (-10.5 / 0.75) + 25` 还原其附图坐标。它位于 `decorations`，只做底图标注，不与海南或其他省份流量重复计数。
- 投影是标准纬线 35°N 的等距圆柱投影：`[longitude * cos(35°), -latitude]`，再等比缩放和平移进入 SVG。全部边界、附图包含在画布内，四周至少 14 SVG 单位留白。投影坐标取到小数点后三位；不是地理坐标精度承诺。
- 输出 `viewBox="0 0 760 542"`。区域字段为 `code / name / shortName / center / path`；`center` 是上游标签点 `cp` 的投影，不是几何重算的质心。
- `decorations` 字段为 `name / path / labelPosition`。附图的岛屿和图框全部按上游保留，不是第 35 个省级统计区域。
- 输出保留上游所有岛屿环；不增加上游缺少的细节，不将这份历史概览资源表述为精密定位图。

生成文件：259,458 bytes；gzip：94,074 bytes；SHA-256：`d0b85e84d9b3225b8fa6545d9df0394e9a4333020a42dd254bb6e6faa281093a`。

## 复现与校验

仓库根目录执行，需要支持 `fetch` 的 Node.js（18+）：

```powershell
node frontend/console-vue/src/relay/assets/china-provinces.build.mjs
node frontend/console-vue/src/relay/assets/china-provinces.build.mjs --check
```

生成器只下载固定 commit 的两个几何输入并检查其 SHA-256。南海数据通过解析 JSON 数组提取，不执行下载的 JavaScript。它校验 34 个不同省级代码、全部环和顶点数量在 SVG 中保持一致、没有 NaN / Infinity，并在 `--check` 模式下将生成结果与现有资产逐字节比较。

## 分发许可

所选上游文件归入该固定版本仓库的 Apache-2.0 许可；`nanhai.js` 与解码源文件也带有 Apache 许可头。上游 LICENSE 列出的 d3 特殊许可文件不是本次引入的文件。

随本资源一并分发本说明文件，其中包含完整上游 LICENSE 和 NOTICE。运行时构建应将此文件作为可读取的“地图来源”链接资产一并输出，避免只打包 SVG 数据而遗漏许可。保留原始声明不表示 Apache 为 ShortLink 背书。

### 上游 NOTICE（完整）

```text
Apache ECharts (incubating)
Copyright 2017-2020 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

### 上游 LICENSE（完整）

```text

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.





========================================================================
Apache ECharts Subcomponents:

The Apache ECharts project contains subcomponents with separate copyright
notices and license terms. Your use of the source code for these
subcomponents is also subject to the terms and conditions of the following
licenses.

BSD 3-Clause (d3.js):
The following files embed [d3.js](https://github.com/d3/d3) BSD 3-Clause:
    `/src/chart/treemap/treemapLayout.js`,
    `/src/chart/tree/layoutHelper.js`,
    `/src/chart/graph/forceHelper.js`,
    `/src/util/number.js`,
    `/src/scale/Time.js`,
See `/licenses/LICENSE-d3` for details of the license.
```
