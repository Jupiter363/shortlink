const BLUE_SCALE = ['#e3ecff', '#bdd0ff', '#89a8f8', '#597eea', '#2f55e7']
const MISSING_COLOR = '#e5eaf1'
const ZERO_COLOR = '#f8fafc'

function province(code, name, shortName, traditional, english, aliases = []) {
  return Object.freeze({
    code,
    name,
    shortName,
    aliases: Object.freeze([
      ...new Set([code, code.slice(0, 2), name, shortName, ...traditional, ...english, ...aliases])
    ])
  })
}

export const PROVINCES = Object.freeze([
  province(
    '110000',
    '北京市',
    '北京',
    [],
    ['Beijing', 'Beijing Municipality', 'Beijing Shi', 'Peking']
  ),
  province('120000', '天津市', '天津', [], ['Tianjin', 'Tianjin Municipality', 'Tianjin Shi']),
  province('130000', '河北省', '河北', [], ['Hebei', 'Hebei Province', 'Hebei Sheng']),
  province('140000', '山西省', '山西', [], ['Shanxi', 'Shanxi Province', 'Shanxi Sheng']),
  province(
    '150000',
    '内蒙古自治区',
    '内蒙古',
    ['內蒙古自治區', '內蒙古'],
    ['Inner Mongolia', 'Inner Mongolia Autonomous Region', 'Nei Mongol', 'Neimenggu', 'Nei Menggu']
  ),
  province(
    '210000',
    '辽宁省',
    '辽宁',
    ['遼寧省', '遼寧'],
    ['Liaoning', 'Liaoning Province', 'Liaoning Sheng']
  ),
  province('220000', '吉林省', '吉林', [], ['Jilin', 'Jilin Province', 'Jilin Sheng']),
  province(
    '230000',
    '黑龙江省',
    '黑龙江',
    ['黑龍江省', '黑龍江'],
    ['Heilongjiang', 'Heilongjiang Province', 'Heilongjiang Sheng']
  ),
  province('310000', '上海市', '上海', [], ['Shanghai', 'Shanghai Municipality', 'Shanghai Shi']),
  province(
    '320000',
    '江苏省',
    '江苏',
    ['江蘇省', '江蘇'],
    ['Jiangsu', 'Jiangsu Province', 'Jiangsu Sheng']
  ),
  province('330000', '浙江省', '浙江', [], ['Zhejiang', 'Zhejiang Province', 'Zhejiang Sheng']),
  province('340000', '安徽省', '安徽', [], ['Anhui', 'Anhui Province', 'Anhui Sheng']),
  province('350000', '福建省', '福建', [], ['Fujian', 'Fujian Province', 'Fujian Sheng']),
  province('360000', '江西省', '江西', [], ['Jiangxi', 'Jiangxi Province', 'Jiangxi Sheng']),
  province(
    '370000',
    '山东省',
    '山东',
    ['山東省', '山東'],
    ['Shandong', 'Shandong Province', 'Shandong Sheng']
  ),
  province('410000', '河南省', '河南', [], ['Henan', 'Henan Province', 'Henan Sheng']),
  province('420000', '湖北省', '湖北', [], ['Hubei', 'Hubei Province', 'Hubei Sheng']),
  province('430000', '湖南省', '湖南', [], ['Hunan', 'Hunan Province', 'Hunan Sheng']),
  province(
    '440000',
    '广东省',
    '广东',
    ['廣東省', '廣東'],
    ['Guangdong', 'Guangdong Province', 'Guangdong Sheng', 'Kwangtung']
  ),
  province(
    '450000',
    '广西壮族自治区',
    '广西',
    ['廣西壯族自治區', '廣西'],
    ['Guangxi', 'Guangxi Zhuang Autonomous Region', 'Guangxi Zhuangzu Zizhiqu'],
    ['广西壮族', '廣西壯族']
  ),
  province('460000', '海南省', '海南', [], ['Hainan', 'Hainan Province', 'Hainan Sheng']),
  province(
    '500000',
    '重庆市',
    '重庆',
    ['重慶市', '重慶'],
    ['Chongqing', 'Chongqing Municipality', 'Chongqing Shi', 'Chungking']
  ),
  province(
    '510000',
    '四川省',
    '四川',
    [],
    ['Sichuan', 'Sichuan Province', 'Sichuan Sheng', 'Szechuan']
  ),
  province(
    '520000',
    '贵州省',
    '贵州',
    ['貴州省', '貴州'],
    ['Guizhou', 'Guizhou Province', 'Guizhou Sheng']
  ),
  province(
    '530000',
    '云南省',
    '云南',
    ['雲南省', '雲南'],
    ['Yunnan', 'Yunnan Province', 'Yunnan Sheng']
  ),
  province(
    '540000',
    '西藏自治区',
    '西藏',
    ['西藏自治區'],
    ['Xizang', 'Tibet', 'Tibet Autonomous Region', 'Xizang Autonomous Region', 'Xizang Zizhiqu']
  ),
  province(
    '610000',
    '陕西省',
    '陕西',
    ['陝西省', '陝西'],
    ['Shaanxi', 'Shaanxi Province', 'Shaanxi Sheng']
  ),
  province(
    '620000',
    '甘肃省',
    '甘肃',
    ['甘肅省', '甘肅'],
    ['Gansu', 'Gansu Province', 'Gansu Sheng']
  ),
  province('630000', '青海省', '青海', [], ['Qinghai', 'Qinghai Province', 'Qinghai Sheng']),
  province(
    '640000',
    '宁夏回族自治区',
    '宁夏',
    ['寧夏回族自治區', '寧夏'],
    ['Ningxia', 'Ningxia Hui Autonomous Region', 'Ningxia Huizu Zizhiqu'],
    ['宁夏回族', '寧夏回族']
  ),
  province(
    '650000',
    '新疆维吾尔自治区',
    '新疆',
    ['新疆維吾爾自治區'],
    [
      'Xinjiang',
      'Xinjiang Uygur Autonomous Region',
      'Xinjiang Uyghur Autonomous Region',
      "Xinjiang Weiwu'er Zizhiqu"
    ],
    ['新疆维吾尔', '新疆維吾爾']
  ),
  province(
    '710000',
    '台湾省',
    '台湾',
    ['臺灣省', '臺灣', '台灣省', '台灣'],
    ['Taiwan', 'Taiwan Province', 'Taiwan Sheng']
  ),
  province(
    '810000',
    '香港特别行政区',
    '香港',
    ['香港特別行政區'],
    [
      'Hong Kong',
      'Hongkong',
      'Hong Kong SAR',
      'Hong Kong Special Administrative Region',
      'Xianggang'
    ],
    ['香港特区', '香港特區']
  ),
  province(
    '820000',
    '澳门特别行政区',
    '澳门',
    ['澳門特別行政區', '澳門'],
    [
      'Macau',
      'Macao',
      'Macau SAR',
      'Macao SAR',
      'Macau Special Administrative Region',
      'Macao Special Administrative Region',
      'Aomen'
    ],
    ['澳门特区', '澳門特區']
  )
])

function normalizedAlias(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return ''
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return ''
  return String(value).normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase()
}

const provinceByAlias = new Map()
for (const entry of PROVINCES) {
  for (const alias of entry.aliases) {
    const key = normalizedAlias(alias)
    const existing = provinceByAlias.get(key)
    provinceByAlias.set(key, existing === undefined || existing === entry.code ? entry.code : null)
  }
}

export function normalizeProvince(value) {
  return provinceByAlias.get(normalizedAlias(value)) ?? null
}

function ratioOrNull(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1
    ? value
    : null
}

function buildLegend(maxCount) {
  if (!maxCount) return []
  const size = Math.min(5, maxCount)
  const max = BigInt(maxCount)
  return Array.from({ length: size }, (_, index) => {
    const min = Number((max * BigInt(index)) / BigInt(size)) + 1
    const upper = Number((max * BigInt(index + 1)) / BigInt(size))
    const colorIndex = size === 1 ? 4 : Math.round((index * 4) / (size - 1))
    return {
      min,
      max: upper,
      color: BLUE_SCALE[colorIndex],
      label: min === upper ? String(min) : `${min}–${upper}`,
      unit: '访问次数'
    }
  })
}

/** Uses only province dimension rows; coverage metadata never supplies synthetic map values. */
export function buildProvinceMap(dimension) {
  if (dimension?.key && dimension.key !== 'province')
    throw new TypeError('Province map requires the province dimension')

  const unmapped = []
  const unknown = []
  const matches = new Map()
  const rows = Array.isArray(dimension?.rows) ? dimension.rows : []
  for (const row of rows) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) continue
    if (row.unknown === true || ['未知', 'unknown'].includes(normalizedAlias(row.label))) {
      unknown.push({ ...row })
      continue
    }
    const code = normalizeProvince(row.label)
    if (!code) {
      unmapped.push({ ...row, reason: 'unrecognized' })
      continue
    }
    const entries = matches.get(code) || []
    entries.push(row)
    matches.set(code, entries)
  }

  const mapped = new Map()
  for (const [code, entries] of matches) {
    // Multiple rows may overlap. Neither summing nor selecting one is a defensible interpretation.
    if (entries.length > 1) {
      unmapped.push(...entries.map((row) => ({ ...row, code, reason: 'conflict' })))
      continue
    }
    const row = entries[0]
    const count = Number.isSafeInteger(row.count) && row.count >= 0 ? row.count : null
    if (count === null) {
      unmapped.push({ ...row, code, reason: 'invalid-count' })
      continue
    }
    mapped.set(code, { count, ratio: ratioOrNull(row.ratio) })
  }

  const maxCount = Math.max(0, ...[...mapped.values()].map((row) => row.count))
  const legend = buildLegend(maxCount)
  const regions = PROVINCES.map(({ code, name, shortName }) => {
    const row = mapped.get(code)
    const count = row?.count ?? null
    return {
      code,
      name,
      shortName,
      count,
      ratio: row?.ratio ?? null,
      state: count === null ? 'missing' : count === 0 ? 'zero' : 'value',
      color:
        count === null
          ? MISSING_COLOR
          : count === 0
            ? ZERO_COLOR
            : legend.find((bin) => count >= bin.min && count <= bin.max).color
    }
  })
  return { regions, unmapped, unknown, legend, maxCount }
}
