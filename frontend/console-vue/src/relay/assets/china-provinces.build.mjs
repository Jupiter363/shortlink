/*
 * Province paths derived from Apache ECharts 4.9.0.
 * Copyright 2017-2020 The Apache Software Foundation.
 * Licensed under the Apache License, Version 2.0.
 * See china-provinces.SOURCE.md for the full license and upstream NOTICE.
 * Modified for ShortLink: decode every upstream ring and project into SVG paths.
 */
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const commit = '90243fca100866ea802249a98df8b0899e68927e'
const base = `https://raw.githubusercontent.com/apache/echarts/${commit}/`
const inputs = {
  map: {
    path: 'map/json/china.json',
    sha256: 'd392f651a48e6213c9bfc83f406711069de296c17f426cffc0ad1148078ee226'
  },
  islands: {
    path: 'src/coord/geo/fix/nanhai.js',
    sha256: '52bee92975de684bd748ed7271a2d550eb6d74d76e7c7415b2ce6d39a34be0e2'
  }
}

async function fetchPinnedSource(source) {
  const response = await fetch(`${base}${source.path}`)
  assert.ok(response.ok, `Cannot download ${source.path}: HTTP ${response.status}`)
  const bytes = Buffer.from(await response.arrayBuffer())
  assert.equal(createHash('sha256').update(bytes).digest('hex'), source.sha256)
  return bytes.toString('utf8')
}

// Equivalent to ECharts 4.9.0 src/coord/geo/parseGeoJson.js decodePolygon.
function decodeRing(encoded, offset, scale) {
  assert.equal(encoded.length % 2, 0, 'Encoded ring must contain coordinate pairs')
  let [x, y] = offset
  const points = []
  for (let i = 0; i < encoded.length; i += 2) {
    const deltaX = encoded.charCodeAt(i) - 64
    const deltaY = encoded.charCodeAt(i + 1) - 64
    x += (deltaX >> 1) ^ -(deltaX & 1)
    y += (deltaY >> 1) ^ -(deltaY & 1)
    points.push([x / scale, y / scale])
  }
  return points
}

const fullNames = {
  110000: '北京市',
  120000: '天津市',
  130000: '河北省',
  140000: '山西省',
  150000: '内蒙古自治区',
  210000: '辽宁省',
  220000: '吉林省',
  230000: '黑龙江省',
  310000: '上海市',
  320000: '江苏省',
  330000: '浙江省',
  340000: '安徽省',
  350000: '福建省',
  360000: '江西省',
  370000: '山东省',
  410000: '河南省',
  420000: '湖北省',
  430000: '湖南省',
  440000: '广东省',
  450000: '广西壮族自治区',
  460000: '海南省',
  500000: '重庆市',
  510000: '四川省',
  520000: '贵州省',
  530000: '云南省',
  540000: '西藏自治区',
  610000: '陕西省',
  620000: '甘肃省',
  630000: '青海省',
  640000: '宁夏回族自治区',
  650000: '新疆维吾尔自治区',
  710000: '台湾省',
  810000: '香港特别行政区',
  820000: '澳门特别行政区'
}

const [mapSource, islandSource] = await Promise.all([
  fetchPinnedSource(inputs.map),
  fetchPinnedSource(inputs.islands)
])
const sourceMap = JSON.parse(mapSource)
assert.equal(sourceMap.type, 'FeatureCollection')
assert.equal(sourceMap.features.length, 34)
assert.equal(sourceMap.UTF8Encoding, true)
const scale = sourceMap.UTF8Scale ?? 1024

const decodedRegions = sourceMap.features
  .map((feature) => {
    const { type, coordinates, encodeOffsets } = feature.geometry
    assert.ok(type === 'Polygon' || type === 'MultiPolygon')
    const polygons = type === 'Polygon' ? [coordinates] : coordinates
    const offsets = type === 'Polygon' ? [encodeOffsets] : encodeOffsets
    const code = String(feature.properties.id)
    assert.ok(fullNames[code], `Unexpected province code ${code}`)
    return {
      code,
      name: fullNames[code],
      shortName: feature.properties.name,
      center: feature.properties.cp,
      polygons: polygons.map((rings, p) =>
        rings.map((ring, r) => decodeRing(ring, offsets[p][r], scale))
      )
    }
  })
  .sort((a, b) => a.code.localeCompare(b.code))
assert.equal(new Set(decodedRegions.map((region) => region.code)).size, 34)

// Read the upstream literal as JSON; never evaluate downloaded JavaScript.
const islandLiteral = islandSource.match(/var points = (\[[\s\S]*?\]);/u)?.[1]
assert.ok(islandLiteral, 'Upstream South China Sea inset point literal is missing')
const insetPoints = JSON.parse(islandLiteral)
assert.equal(insetPoints.length, 12)
const islandOrigin = JSON.parse(islandSource.match(/var geoCoord = (\[[^;]+\]);/u)?.[1] ?? 'null')
assert.deepEqual(islandOrigin, [126, 25])
const insetRings = insetPoints.map((ring) =>
  ring.map(([x, y]) => [x / 10.5 + islandOrigin[0], y / (-10.5 / 0.75) + islandOrigin[1]])
)

// Equirectangular projection with standard parallel 35 degrees north.
// Every vertex, island and interior ring is retained; there is no simplification.
const cosParallel = Math.cos((35 * Math.PI) / 180)
const rawProject = ([longitude, latitude]) => [longitude * cosParallel, -latitude]
const allPoints = [
  ...decodedRegions.flatMap((region) => region.polygons.flat(2)),
  ...insetRings.flat()
]
const projected = allPoints.map(rawProject)
const minX = Math.min(...projected.map(([x]) => x))
const maxX = Math.max(...projected.map(([x]) => x))
const minY = Math.min(...projected.map(([, y]) => y))
const maxY = Math.max(...projected.map(([, y]) => y))
const width = 760
const padding = 14
const unitScale = (width - padding * 2) / (maxX - minX)
const height = Math.ceil((maxY - minY) * unitScale + padding * 2)
const rounded = (number) => Number(number.toFixed(3))
const project = (point) => {
  const [x, y] = rawProject(point)
  return [rounded(padding + (x - minX) * unitScale), rounded(padding + (y - minY) * unitScale)]
}
const ringPath = (ring) =>
  ring.map((point, index) => `${index ? 'L' : 'M'}${project(point).join(',')}`).join('') + 'Z'

const regions = decodedRegions.map(({ polygons, center, ...region }) => ({
  ...region,
  center: project(center),
  path: polygons.flatMap((rings) => rings.map(ringPath)).join('')
}))
const decorations = [
  {
    name: '南海诸岛',
    path: insetRings.map(ringPath).join(''),
    labelPosition: project([islandOrigin[0] + 64 / 10.5 / 2, islandOrigin[1] + 99 / (-10.5 / 0.75)])
  }
]
const output = {
  viewBox: `0 0 ${width} ${height}`,
  width,
  height,
  source: {
    project: 'Apache ECharts',
    version: '4.9.0',
    commit,
    license: 'Apache-2.0',
    attribution: 'Copyright 2017-2020 The Apache Software Foundation',
    notice:
      'Apache ECharts (incubating)\nCopyright 2017-2020 The Apache Software Foundation\n\nThis product includes software developed at\nThe Apache Software Foundation (http://www.apache.org/).',
    changes:
      'Decoded UTF8 rings and projected all source vertices and the upstream South China Sea inset into SVG paths; no boundary simplification or manual drawing.',
    projection:
      'Equirectangular, standard parallel 35°N, SVG Y axis inverted; rounded to 0.001 SVG units.',
    inputs
  },
  regions,
  decorations
}

const ringCount = decodedRegions.reduce(
  (sum, region) => sum + region.polygons.reduce((count, rings) => count + rings.length, 0),
  0
)
const vertexCount = decodedRegions.reduce(
  (sum, region) => sum + region.polygons.flat().reduce((count, ring) => count + ring.length, 0),
  0
)
const serialized = JSON.stringify(output) + '\n'
assert.equal(
  (
    regions
      .map(({ path }) => path)
      .join('')
      .match(/M/gu) ?? []
  ).length,
  ringCount
)
assert.equal(
  (
    regions
      .map(({ path }) => path)
      .join('')
      .match(/[ML]/gu) ?? []
  ).length,
  vertexCount
)
assert.ok(!serialized.includes('NaN') && !serialized.includes('Infinity'))
const outputPath = fileURLToPath(new URL('./china-provinces.json', import.meta.url))
if (process.argv.includes('--check')) {
  assert.equal(
    await readFile(outputPath, 'utf8'),
    serialized,
    'Generated province asset differs from committed output'
  )
} else {
  await writeFile(outputPath, serialized)
}
console.log(
  JSON.stringify(
    {
      output: outputPath,
      viewBox: output.viewBox,
      provinces: regions.length,
      provinceRings: ringCount,
      provinceVertices: vertexCount,
      insetRings: insetRings.length,
      bytes: Buffer.byteLength(serialized),
      sha256: createHash('sha256').update(serialized).digest('hex')
    },
    null,
    2
  )
)
