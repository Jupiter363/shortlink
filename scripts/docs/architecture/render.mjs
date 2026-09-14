#!/usr/bin/env node
/** Rasterize a self-contained, path-only SVG at its declared pixel dimensions. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SHARP_ROOT = process.env.ARCHITECTURE_SHARP_MODULE || 'sharp';
const require = createRequire(import.meta.url);
const MAX_SVG_BYTES = 64 * 1024 * 1024;
const MAX_PIXELS = 64 * 1024 * 1024;
const MAX_QA_PIXELS = 256 * 1024 * 1024;

function check(ok, message) {
  if (!ok) throw new Error(message);
}

function argumentsFor(argv) {
  const options = { qaDir: path.join(HERE, 'qa'), overwrite: false };
  const keys = new Map([
    ['--svg', 'svg'], ['--png', 'png'], ['--layout', 'layout'], ['--qa-dir', 'qaDir'],
  ]);
  const seen = new Set();
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    check(!seen.has(flag), `Duplicate option: ${flag}`);
    seen.add(flag);
    if (flag === '--overwrite') { options.overwrite = true; continue; }
    if (flag === '--help') { options.help = true; continue; }
    check(keys.has(flag) && argv[i + 1] && !argv[i + 1].startsWith('--'), `Invalid option: ${flag}`);
    options[keys.get(flag)] = argv[++i];
  }
  if (options.help) return options;
  check(options.svg && options.png, '--svg and --png are required');
  for (const key of ['svg', 'png', 'layout', 'qaDir']) {
    if (options[key]) options[key] = path.resolve(options[key]);
  }
  check(path.extname(options.png).toLowerCase() === '.png', 'Output must have a .png extension');
  check(options.png !== options.svg && options.png !== options.layout, 'Output must not replace an input');
  return options;
}

function attributes(tag) {
  const attrs = new Map();
  for (const match of tag.matchAll(/([\w:.-]+)\s*=\s*(["'])(.*?)\2/gs)) {
    check(!attrs.has(match[1]), `Duplicate SVG attribute: ${match[1]}`);
    attrs.set(match[1], match[3]);
  }
  return attrs;
}

function dimension(value, name) {
  check(typeof value === 'string' && /^\d+(?:\.\d+)?(?:px)?$/.test(value), `SVG ${name} must be an explicit positive px value`);
  const n = Number(value.replace(/px$/, ''));
  check(Number.isSafeInteger(n) && n > 0 && n <= 16384, `SVG ${name} must be an integer between 1 and 16384`);
  return n;
}

function inspectSvg(svg) {
  const content = svg.replace(/<!--[\s\S]*?-->/g, '');
  check(!/<!DOCTYPE|<!ENTITY/i.test(content), 'External entities and document types are not supported');
  check(!/<(?:[\w.-]+:)?(?:image|text|tspan|textPath|foreignObject|script)\b/i.test(content),
    'SVG must contain vector paths/shapes only: no bitmap, live text, foreignObject or script');
  check(!/@import\b|@font-face\b/i.test(content), 'External styles or browser font resolution are not supported');
  for (const m of content.matchAll(/\b(?:xlink:)?href\s*=\s*(["'])(.*?)\1/gs)) {
    check(/^#[^\s]+$/.test(m[2]), 'Only SVG-local fragment href references are allowed');
  }
  for (const m of content.matchAll(/url\(\s*(["']?)(.*?)\1\s*\)/gs)) {
    check(/^#[^\s]+$/.test(m[2].trim()), 'Only SVG-local fragment url references are allowed');
  }
  const opening = content.match(/<svg\b[^>]*>/s);
  check(opening, 'No SVG root element found');
  const attrs = attributes(opening[0]);
  const width = dimension(attrs.get('width'), 'width');
  const height = dimension(attrs.get('height'), 'height');
  check(width * height <= MAX_PIXELS, 'SVG exceeds the 64 Mi-pixel raster budget');
  let viewBox = [0, 0, width, height];
  if (attrs.has('viewBox')) {
    viewBox = attrs.get('viewBox').trim().split(/[\s,]+/).map(Number);
    check(viewBox.length === 4 && viewBox.every(Number.isFinite) && viewBox[2] > 0 && viewBox[3] > 0, 'Invalid SVG viewBox');
  }
  // Crops use a linear viewBox-to-pixel mapping. Reject letterboxed mappings.
  const scaleX = width / viewBox[2];
  const scaleY = height / viewBox[3];
  const aspect = (attrs.get('preserveAspectRatio') || 'xMidYMid meet').trim();
  check(aspect === 'none' || Math.abs(scaleX - scaleY) <= 1e-9 * Math.max(scaleX, scaleY),
    'Crops require matching viewport/viewBox aspect ratios, or preserveAspectRatio="none"');
  return { width, height, viewBox, scaleX, scaleY,
    pathCount: (content.match(/<path\b/g) || []).length,
    liveTextCount: 0, bitmapCount: 0 };
}

function planCrops(layout, geometry) {
  check(layout && typeof layout === 'object' && !Array.isArray(layout), 'Layout must be an object');
  const { viewBox, width, height, scaleX, scaleY } = geometry;
  const [vx, vy, vw, vh] = viewBox;
  check(layout.canvas && layout.canvas.width === vw && layout.canvas.height === vh,
    'layout.canvas width/height must equal the SVG viewBox coordinate dimensions');
  check((layout.canvas.x ?? 0) === vx && (layout.canvas.y ?? 0) === vy,
    'layout.canvas x/y must equal the SVG viewBox origin');
  check(Array.isArray(layout.regions) && layout.regions.length <= 512, 'layout.regions must contain at most 512 rectangles');
  const ids = new Set();
  let pixels = 0;
  const crops = layout.regions.map((r, index) => {
    check(r && typeof r.id === 'string' && r.id.length > 0 && r.id.length <= 160, 'Each region needs a bounded string id');
    check(!ids.has(r.id), `Duplicate region id: ${r.id}`);
    ids.add(r.id);
    check([r.x, r.y, r.width, r.height].every(Number.isFinite) && r.width > 0 && r.height > 0,
      `Invalid region rectangle: ${r.id}`);
    const epsilon = 1e-7;
    check(r.x >= vx - epsilon && r.y >= vy - epsilon && r.x + r.width <= vx + vw + epsilon && r.y + r.height <= vy + vh + epsilon,
      `Region lies outside the SVG viewBox: ${r.id}`);
    const left = Math.max(0, Math.floor((r.x - vx) * scaleX));
    const top = Math.max(0, Math.floor((r.y - vy) * scaleY));
    const right = Math.min(width, Math.ceil((r.x + r.width - vx) * scaleX));
    const bottom = Math.min(height, Math.ceil((r.y + r.height - vy) * scaleY));
    check(right > left && bottom > top, `Region has no export pixels: ${r.id}`);
    const extract = { left, top, width: right - left, height: bottom - top };
    pixels += extract.width * extract.height;
    const slug = r.id.replace(/[^\p{L}\p{N}._-]+/gu, '-').replace(/^\.+/, '').slice(0, 90) || 'region';
    return { id: r.id, label: r.label ?? null, sourceRectangle: { x: r.x, y: r.y, width: r.width, height: r.height },
      extract, filename: `${String(index + 1).padStart(3, '0')}-${slug}.png` };
  });
  check(pixels <= MAX_QA_PIXELS, 'QA crops exceed the aggregate 256 Mi-pixel budget');
  return crops;
}

async function smallRegularFile(filename, maxBytes) {
  const info = await fs.lstat(filename);
  check(info.isFile() && !info.isSymbolicLink() && info.size > 0 && info.size <= maxBytes, `Invalid input file: ${filename}`);
  return fs.readFile(filename);
}

const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

async function writableOutput(filename, overwrite) {
  try {
    const info = await fs.lstat(filename);
    check(info.isFile() && !info.isSymbolicLink(), `Output is not a regular file: ${filename}`);
    check(overwrite, `Output already exists; use --overwrite: ${filename}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

async function save(filename, bytes, overwrite) {
  await fs.mkdir(path.dirname(filename), { recursive: true });
  // Exclusive creation is the default; an explicit overwrite only affects named outputs.
  await fs.writeFile(filename, bytes, { flag: overwrite ? 'w' : 'wx' });
}

async function main() {
  const options = argumentsFor(process.argv.slice(2));
  if (options.help) {
    console.log('node render.mjs --svg INPUT.svg --png OUTPUT.png [--layout layout.json] [--qa-dir DIR] [--overwrite]');
    return;
  }
  const svgBytes = await smallRegularFile(options.svg, MAX_SVG_BYTES);
  const geometry = inspectSvg(svgBytes.toString('utf8'));
  let layoutBytes;
  let crops = [];
  if (options.layout) {
    layoutBytes = await smallRegularFile(options.layout, 8 * 1024 * 1024);
    crops = planCrops(JSON.parse(layoutBytes.toString('utf8').replace(/^\uFEFF/, '')), geometry);
  }
  const qaManifest = path.join(options.qaDir, 'render-manifest.json');
  const outputs = [options.png, ...(options.layout ? [qaManifest] : []),
    ...crops.map(c => path.join(options.qaDir, c.filename))];
  check(new Set(outputs).size === outputs.length, 'Output paths must be distinct');
  for (const output of outputs) {
    check(output !== options.svg && output !== options.layout, 'A generated file would replace an input');
    await writableOutput(output, options.overwrite);
  }

  const sharp = require(SHARP_ROOT);
  sharp.concurrency(2);
  sharp.cache({ memory: 64, files: 0, items: 16 });
  const pngBytes = await sharp(svgBytes, { density: 72, limitInputPixels: MAX_PIXELS, failOn: 'error' })
    .png({ compressionLevel: 6 }).toBuffer();
  const metadata = await sharp(pngBytes).metadata();
  check(metadata.format === 'png' && metadata.width === geometry.width && metadata.height === geometry.height,
    `Raster dimensions differ from explicit SVG dimensions: ${metadata.width}x${metadata.height}`);
  await save(options.png, pngBytes, options.overwrite);
  const written = await sharp(options.png).metadata();
  check(written.format === 'png' && written.width === geometry.width && written.height === geometry.height,
    'Written PNG dimension verification failed');

  const cropResults = [];
  for (const crop of crops) {
    const cropPath = path.join(options.qaDir, crop.filename);
    const bytes = await sharp(pngBytes).extract(crop.extract).png({ compressionLevel: 6 }).toBuffer();
    await save(cropPath, bytes, options.overwrite);
    const actual = await sharp(cropPath).metadata();
    check(actual.width === crop.extract.width && actual.height === crop.extract.height, `Crop dimension mismatch: ${crop.id}`);
    cropResults.push({ ...crop, path: cropPath, width: actual.width, height: actual.height, sha256: hash(bytes) });
  }
  const result = { status: 'PASS', scope: 'Raster size and lossless pixel crops; human text/arrow QA is separate',
    renderedAt: new Date().toISOString(), rendererSha256: hash(await fs.readFile(fileURLToPath(import.meta.url))),
    svg: { path: options.svg, sha256: hash(svgBytes), ...geometry },
    png: { path: options.png, sha256: hash(pngBytes), bytes: pngBytes.length, width: written.width, height: written.height },
    layout: options.layout ? { path: options.layout, sha256: hash(layoutBytes) } : null,
    sharpVersion: sharp.versions.sharp, vipsVersion: sharp.versions.vips, crops: cropResults };
  if (options.layout) await save(qaManifest, Buffer.from(JSON.stringify(result, null, 2) + '\n'), options.overwrite);
  console.log(JSON.stringify(result, null, 2));
}

main().catch(error => {
  console.error(JSON.stringify({ status: 'FAIL', message: error.message }));
  process.exitCode = 1;
});
