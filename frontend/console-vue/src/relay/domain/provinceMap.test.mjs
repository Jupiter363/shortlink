import assert from 'node:assert/strict'
import test from 'node:test'
import { dimensionView } from './analytics.js'
import { PROVINCES, buildProvinceMap, normalizeProvince } from './provinceMap.js'

const dimension = (rows, quality = {}) => ({ key: 'province', rows, quality })
const region = (model, code) => model.regions.find((entry) => entry.code === code)

test('maps all 34 province-level units and their explicit aliases without collisions', () => {
  assert.equal(PROVINCES.length, 34)
  assert.equal(new Set(PROVINCES.map((entry) => entry.code)).size, 34)
  for (const entry of PROVINCES) {
    assert.match(entry.code, /^\d{6}$/)
    assert.equal(normalizeProvince(entry.name), entry.code)
    assert.equal(normalizeProvince(entry.shortName), entry.code)
    assert.equal(normalizeProvince(Number(entry.code)), entry.code)
    for (const alias of entry.aliases) assert.equal(normalizeProvince(alias), entry.code, alias)
  }
  assert.equal(normalizeProvince('廣東省'), '440000')
  assert.equal(normalizeProvince('  Inner   Mongolia  '), '150000')
  assert.equal(normalizeProvince('sHaAnXi'), '610000')
  assert.equal(normalizeProvince('Shanxi'), '140000')
  assert.equal(normalizeProvince('４４００００'), '440000')
  assert.equal(normalizeProvince('台灣'), '710000')
  assert.equal(normalizeProvince('Hong Kong'), '810000')
  assert.equal(normalizeProvince('Macao'), '820000')
})

test('rejects fuzzy, city-level, mixed-scope and abnormal names instead of guessing', () => {
  for (const name of [
    null,
    undefined,
    {},
    [],
    true,
    NaN,
    Infinity,
    '',
    '未知',
    'unknown',
    '中国',
    'CN',
    '华南',
    '广东省广州市',
    'Guangdong, China',
    '广东/广西',
    '山 陕西',
    'shan xi',
    '440100',
    440100,
    'Guangdon',
    '<广东>'
  ])
    assert.equal(normalizeProvince(name), null, String(name))
  assert.throws(() => buildProvinceMap({ key: 'country', rows: [] }), /province dimension/)
})

test('maps a complete 34-region response without losing province identities or original values', () => {
  const rows = PROVINCES.map((entry, index) => ({
    label: entry.aliases.at(-1),
    count: index + 1,
    ratio: (index + 1) / 1000
  }))
  const model = buildProvinceMap(dimension(rows))
  for (const [index, entry] of PROVINCES.entries()) {
    const mapped = region(model, entry.code)
    assert.equal(mapped.name, entry.name)
    assert.equal(mapped.shortName, entry.shortName)
    assert.equal(mapped.state, 'value')
    assert.equal(mapped.count, rows[index].count)
    assert.equal(mapped.ratio, rows[index].ratio)
  }
  assert.deepEqual(model.unmapped, [])
  assert.deepEqual(model.unknown, [])
})

test('distinguishes missing, explicit zero, mapped values, province unknown and unmapped rows', () => {
  const unknown = { label: '未知', count: 3, ratio: 0.3, unknown: true }
  const unmapped = { label: '华南', count: 4, ratio: 0.4 }
  const model = buildProvinceMap(
    dimension(
      [
        { label: '北京', count: 0, ratio: 0 },
        { label: '广东', count: 2, ratio: 0.2 },
        unknown,
        unmapped
      ],
      { status: 'PARTIAL', unknownCount: 99 }
    )
  )
  assert.equal(model.regions.length, 34)
  assert.deepEqual([region(model, '110000').state, region(model, '110000').count], ['zero', 0])
  assert.deepEqual([region(model, '440000').state, region(model, '440000').count], ['value', 2])
  assert.deepEqual(
    [region(model, '310000').state, region(model, '310000').count, region(model, '310000').ratio],
    ['missing', null, null]
  )
  assert.notEqual(region(model, '110000').color, region(model, '310000').color)
  assert.deepEqual(model.unknown, [unknown])
  assert.deepEqual(model.unmapped, [{ ...unmapped, reason: 'unrecognized' }])
  assert.equal(model.maxCount, 2)
})

test('leaves absent provinces missing even when metadata claims complete coverage', () => {
  const model = buildProvinceMap(
    dimension([], { status: 'AVAILABLE', knownCount: 0, eligibleCount: 0 })
  )
  assert.ok(model.regions.every((entry) => entry.state === 'missing' && entry.count === null))
  assert.deepEqual(model.legend, [])
  assert.equal(model.maxCount, 0)
  assert.deepEqual(buildProvinceMap(null).regions, model.regions)
})

test('retains server ratios rather than normalizing mapped regions or unknown rows', () => {
  const province = dimensionView(
    {
      summary: {
        pv: 100,
        localeCnStats: [
          { label: '广东省', count: 6, ratio: 0.06 },
          { label: '江苏省', count: 2, ratio: null }
        ],
        dimensionQuality: {
          localeCnStats: {
            status: 'PARTIAL',
            knownCount: 8,
            unknownCount: 2,
            eligibleCount: 10,
            coverage: 0.8
          },
          countryStats: { status: 'PARTIAL', unknownCount: 90 }
        }
      },
      meta: { dimensionQuality: {}, approximation: {} }
    },
    'province'
  )
  const model = buildProvinceMap(province)
  assert.equal(region(model, '440000').ratio, 0.06)
  assert.equal(region(model, '320000').ratio, 0.2)
  assert.equal(model.unknown.length, 1)
  assert.equal(model.unknown[0].count, 2)
  assert.equal(model.unknown[0].ratio, 0.2)
  const missingRatio = buildProvinceMap(dimension([{ label: '广东', count: 9, ratio: null }]))
  assert.equal(region(missingRatio, '440000').ratio, null)
})

test('preserves every conflicting alias row and never adds or chooses overlapping counts', () => {
  for (const secondCount of [3, 5]) {
    const rows = [
      { label: '广东', count: 3, ratio: 0.03 },
      { label: 'Guangdong Province', count: secondCount, ratio: 0.05 },
      { label: '上海', count: 1, ratio: 0.01 }
    ]
    const before = structuredClone(rows)
    const model = buildProvinceMap(dimension(rows))
    assert.equal(region(model, '440000').state, 'missing')
    assert.equal(region(model, '440000').count, null)
    assert.deepEqual(
      model.unmapped,
      rows.slice(0, 2).map((row) => ({ ...row, code: '440000', reason: 'conflict' }))
    )
    assert.equal(model.maxCount, 1)
    assert.deepEqual(rows, before)
  }
})

test('keeps invalid counts out of map colors without discarding the original row', () => {
  for (const count of [
    null,
    undefined,
    '',
    ' ',
    '0',
    '2',
    -1,
    1.5,
    true,
    NaN,
    Infinity,
    Number.MAX_SAFE_INTEGER + 1
  ]) {
    const row = { label: '广东', count, ratio: 0.5 }
    const model = buildProvinceMap(dimension([row]))
    assert.equal(region(model, '440000').state, 'missing')
    assert.deepEqual(model.unmapped, [{ ...row, code: '440000', reason: 'invalid-count' }])
    assert.deepEqual(model.legend, [])
  }
})

test('builds non-overlapping positive integer legend ranges across small and extreme counts', () => {
  for (const max of [0, 1, 2, 3, 4, 5, 6, 7, 100, 10001, 1e12, Number.MAX_SAFE_INTEGER]) {
    const model = buildProvinceMap(dimension([{ label: '北京', count: max, ratio: 1 }]))
    const bins = model.legend
    assert.equal(bins.length, Math.min(5, max))
    if (!max) {
      assert.equal(region(model, '110000').state, 'zero')
      continue
    }
    assert.equal(bins[0].min, 1)
    assert.equal(bins.at(-1).max, max)
    for (const [index, bin] of bins.entries()) {
      assert.ok(Number.isSafeInteger(bin.min) && Number.isSafeInteger(bin.max))
      assert.ok(bin.min <= bin.max)
      assert.equal(bin.unit, '访问次数')
      if (index) assert.equal(bin.min, bins[index - 1].max + 1)
      for (const value of [bin.min, bin.max]) {
        assert.equal(bins.filter((item) => value >= item.min && value <= item.max).length, 1)
      }
    }
    assert.equal(region(model, '110000').color, bins.at(-1).color)
  }
})
