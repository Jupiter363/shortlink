import { h, ref, computed, watch, nextTick, onMounted, onBeforeUnmount, useAttrs } from '/vendor/vue.js';

/*
 * Official Phosphor SVG paths, unchanged from frontend-style-samples/icons.js.
 * Source commit: 2b75f3ad12b420c9504ef05df8d2564a28f8500e.
 * MIT License
 *
 * Copyright (c) 2023 Phosphor Icons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
const PHOSPHOR_ICONS = Object.freeze({
  "browser": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M224,56V96H32V56a8,8,0,0,1,8-8H216A8,8,0,0,1,224,56Z\" opacity=\"0.16\" /><path d=\"M216,40H40A16,16,0,0,0,24,56V200a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40Zm0,16V88H40V56Zm0,144H40V104H216v96Z\" /></g></svg>",
  "link": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M218.34,119.6,183.6,154.34a46.58,46.58,0,0,1-44.31,12.26c-.31.34-.62.67-.95,1L103.6,202.34A46.63,46.63,0,1,1,37.66,136.4L72.4,101.66A46.6,46.6,0,0,1,116.71,89.4c.31-.34.62-.67,1-1L152.4,53.66a46.63,46.63,0,0,1,65.94,65.94Z\" opacity=\"0.16\" /><path d=\"M240,88.23a54.43,54.43,0,0,1-16,37L189.25,160a54.27,54.27,0,0,1-38.63,16h-.05A54.63,54.63,0,0,1,96,119.84a8,8,0,0,1,16,.45A38.62,38.62,0,0,0,150.58,160h0a38.39,38.39,0,0,0,27.31-11.31l34.75-34.75a38.63,38.63,0,0,0-54.63-54.63l-11,11A8,8,0,0,1,135.7,59l11-11A54.65,54.65,0,0,1,224,48,54.86,54.86,0,0,1,240,88.23ZM109,185.66l-11,11A38.41,38.41,0,0,1,70.6,208h0a38.63,38.63,0,0,1-27.29-65.94L78,107.31A38.63,38.63,0,0,1,144,135.71a8,8,0,0,0,7.78,8.22H152a8,8,0,0,0,8-7.78A54.86,54.86,0,0,0,144,96a54.65,54.65,0,0,0-77.27,0L32,130.75A54.62,54.62,0,0,0,70.56,224h0a54.28,54.28,0,0,0,38.64-16l11-11A8,8,0,0,0,109,185.66Z\" /></g></svg>",
  "globe": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M224,128a96,96,0,1,1-96-96A96,96,0,0,1,224,128Z\" opacity=\"0.16\" /><path d=\"M128,24h0A104,104,0,1,0,232,128,104.12,104.12,0,0,0,128,24Zm88,104a87.61,87.61,0,0,1-3.33,24H174.16a157.44,157.44,0,0,0,0-48h38.51A87.61,87.61,0,0,1,216,128ZM102,168H154a115.11,115.11,0,0,1-26,45A115.27,115.27,0,0,1,102,168Zm-3.9-16a140.84,140.84,0,0,1,0-48h59.88a140.84,140.84,0,0,1,0,48ZM40,128a87.61,87.61,0,0,1,3.33-24H81.84a157.44,157.44,0,0,0,0,48H43.33A87.61,87.61,0,0,1,40,128ZM154,88H102a115.11,115.11,0,0,1,26-45A115.27,115.27,0,0,1,154,88Zm52.33,0H170.71a135.28,135.28,0,0,0-22.3-45.6A88.29,88.29,0,0,1,206.37,88ZM107.59,42.4A135.28,135.28,0,0,0,85.29,88H49.63A88.29,88.29,0,0,1,107.59,42.4ZM49.63,168H85.29a135.28,135.28,0,0,0,22.3,45.6A88.29,88.29,0,0,1,49.63,168Zm98.78,45.6a135.28,135.28,0,0,0,22.3-45.6h35.66A88.29,88.29,0,0,1,148.41,213.6Z\" /></g></svg>",
  "database": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M216,80c0,26.51-39.4,48-88,48S40,106.51,40,80s39.4-48,88-48S216,53.49,216,80Z\" opacity=\"0.16\" /><path d=\"M128,24C74.17,24,32,48.6,32,80v96c0,31.4,42.17,56,96,56s96-24.6,96-56V80C224,48.6,181.83,24,128,24Zm80,104c0,9.62-7.88,19.43-21.61,26.92C170.93,163.35,150.19,168,128,168s-42.93-4.65-58.39-13.08C55.88,147.43,48,137.62,48,128V111.36c17.06,15,46.23,24.64,80,24.64s62.94-9.68,80-24.64ZM69.61,53.08C85.07,44.65,105.81,40,128,40s42.93,4.65,58.39,13.08C200.12,60.57,208,70.38,208,80s-7.88,19.43-21.61,26.92C170.93,115.35,150.19,120,128,120s-42.93-4.65-58.39-13.08C55.88,99.43,48,89.62,48,80S55.88,60.57,69.61,53.08ZM186.39,202.92C170.93,211.35,150.19,216,128,216s-42.93-4.65-58.39-13.08C55.88,195.43,48,185.62,48,176V159.36c17.06,15,46.23,24.64,80,24.64s62.94-9.68,80-24.64V176C208,185.62,200.12,195.43,186.39,202.92Z\" /></g></svg>",
  "user": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M192,96a64,64,0,1,1-64-64A64,64,0,0,1,192,96Z\" opacity=\"0.16\" /><path d=\"M230.92,212c-15.23-26.33-38.7-45.21-66.09-54.16a72,72,0,1,0-73.66,0C63.78,166.78,40.31,185.66,25.08,212a8,8,0,1,0,13.85,8c18.84-32.56,52.14-52,89.07-52s70.23,19.44,89.07,52a8,8,0,1,0,13.85-8ZM72,96a56,56,0,1,1,56,56A56.06,56.06,0,0,1,72,96Z\" /></g></svg>",
  "server": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M216,152v48a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V152a8,8,0,0,1,8-8H208A8,8,0,0,1,216,152ZM208,48H48a8,8,0,0,0-8,8v48a8,8,0,0,0,8,8H208a8,8,0,0,0,8-8V56A8,8,0,0,0,208,48Z\" opacity=\"0.16\" /><path d=\"M208,136H48a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V152A16,16,0,0,0,208,136Zm0,64H48V152H208v48Zm0-160H48A16,16,0,0,0,32,56v48a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V56A16,16,0,0,0,208,40Zm0,64H48V56H208v48ZM192,80a12,12,0,1,1-12-12A12,12,0,0,1,192,80Zm0,96a12,12,0,1,1-12-12A12,12,0,0,1,192,176Z\" /></g></svg>",
  "gear": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M207.86,123.18l16.78-21a99.14,99.14,0,0,0-10.07-24.29l-26.7-3a81,81,0,0,0-6.81-6.81l-3-26.71a99.43,99.43,0,0,0-24.3-10l-21,16.77a81.59,81.59,0,0,0-9.64,0l-21-16.78A99.14,99.14,0,0,0,77.91,41.43l-3,26.7a81,81,0,0,0-6.81,6.81l-26.71,3a99.43,99.43,0,0,0-10,24.3l16.77,21a81.59,81.59,0,0,0,0,9.64l-16.78,21a99.14,99.14,0,0,0,10.07,24.29l26.7,3a81,81,0,0,0,6.81,6.81l3,26.71a99.43,99.43,0,0,0,24.3,10l21-16.77a81.59,81.59,0,0,0,9.64,0l21,16.78a99.14,99.14,0,0,0,24.29-10.07l3-26.7a81,81,0,0,0,6.81-6.81l26.71-3a99.43,99.43,0,0,0,10-24.3l-16.77-21A81.59,81.59,0,0,0,207.86,123.18ZM128,168a40,40,0,1,1,40-40A40,40,0,0,1,128,168Z\" opacity=\"0.16\" /><path d=\"M128,80a48,48,0,1,0,48,48A48.05,48.05,0,0,0,128,80Zm0,80a32,32,0,1,1,32-32A32,32,0,0,1,128,160Zm88-29.84q.06-2.16,0-4.32l14.92-18.64a8,8,0,0,0,1.48-7.06,107.6,107.6,0,0,0-10.88-26.25,8,8,0,0,0-6-3.93l-23.72-2.64q-1.48-1.56-3-3L186,40.54a8,8,0,0,0-3.94-6,107.29,107.29,0,0,0-26.25-10.86,8,8,0,0,0-7.06,1.48L130.16,40Q128,40,125.84,40L107.2,25.11a8,8,0,0,0-7.06-1.48A107.6,107.6,0,0,0,73.89,34.51a8,8,0,0,0-3.93,6L67.32,64.27q-1.56,1.49-3,3L40.54,70a8,8,0,0,0-6,3.94,107.71,107.71,0,0,0-10.87,26.25,8,8,0,0,0,1.49,7.06L40,125.84Q40,128,40,130.16L25.11,148.8a8,8,0,0,0-1.48,7.06,107.6,107.6,0,0,0,10.88,26.25,8,8,0,0,0,6,3.93l23.72,2.64q1.49,1.56,3,3L70,215.46a8,8,0,0,0,3.94,6,107.71,107.71,0,0,0,26.25,10.87,8,8,0,0,0,7.06-1.49L125.84,216q2.16.06,4.32,0l18.64,14.92a8,8,0,0,0,7.06,1.48,107.21,107.21,0,0,0,26.25-10.88,8,8,0,0,0,3.93-6l2.64-23.72q1.56-1.48,3-3L215.46,186a8,8,0,0,0,6-3.94,107.71,107.71,0,0,0,10.87-26.25,8,8,0,0,0-1.49-7.06Zm-16.1-6.5a73.93,73.93,0,0,1,0,8.68,8,8,0,0,0,1.74,5.48l14.19,17.73a91.57,91.57,0,0,1-6.23,15L187,173.11a8,8,0,0,0-5.1,2.64,74.11,74.11,0,0,1-6.14,6.14,8,8,0,0,0-2.64,5.1l-2.51,22.58a91.32,91.32,0,0,1-15,6.23l-17.74-14.19a8,8,0,0,0-5-1.75h-.48a73.93,73.93,0,0,1-8.68,0,8.06,8.06,0,0,0-5.48,1.74L100.45,215.8a91.57,91.57,0,0,1-15-6.23L82.89,187a8,8,0,0,0-2.64-5.1,74.11,74.11,0,0,1-6.14-6.14,8,8,0,0,0-5.1-2.64L46.43,170.6a91.32,91.32,0,0,1-6.23-15l14.19-17.74a8,8,0,0,0,1.74-5.48,73.93,73.93,0,0,1,0-8.68,8,8,0,0,0-1.74-5.48L40.2,100.45a91.57,91.57,0,0,1,6.23-15L69,82.89a8,8,0,0,0,5.1-2.64,74.11,74.11,0,0,1,6.14-6.14A8,8,0,0,0,82.89,69L85.4,46.43a91.32,91.32,0,0,1,15-6.23l17.74,14.19a8,8,0,0,0,5.48,1.74,73.93,73.93,0,0,1,8.68,0,8.06,8.06,0,0,0,5.48-1.74L155.55,40.2a91.57,91.57,0,0,1,15,6.23L173.11,69a8,8,0,0,0,2.64,5.1,74.11,74.11,0,0,1,6.14,6.14,8,8,0,0,0,5.1,2.64l22.58,2.51a91.32,91.32,0,0,1,6.23,15l-14.19,17.74A8,8,0,0,0,199.87,123.66Z\" /></g></svg>",
  "code": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M208,88H152V32Z\" opacity=\"0.16\" /><path d=\"M181.66,146.34a8,8,0,0,1,0,11.32l-24,24a8,8,0,0,1-11.32-11.32L164.69,152l-18.35-18.34a8,8,0,0,1,11.32-11.32Zm-72-24a8,8,0,0,0-11.32,0l-24,24a8,8,0,0,0,0,11.32l24,24a8,8,0,0,0,11.32-11.32L91.31,152l18.35-18.34A8,8,0,0,0,109.66,122.34ZM216,88V216a16,16,0,0,1-16,16H56a16,16,0,0,1-16-16V40A16,16,0,0,1,56,24h96a8,8,0,0,1,5.66,2.34l56,56A8,8,0,0,1,216,88Zm-56-8h28.69L160,51.31Zm40,136V96H152a8,8,0,0,1-8-8V40H56V216H200Z\" /></g></svg>",
  "robot": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M200,56H56A24,24,0,0,0,32,80V192a24,24,0,0,0,24,24H200a24,24,0,0,0,24-24V80A24,24,0,0,0,200,56ZM164,184H92a20,20,0,0,1,0-40h72a20,20,0,0,1,0,40Z\" opacity=\"0.16\" /><path d=\"M200,48H136V16a8,8,0,0,0-16,0V48H56A32,32,0,0,0,24,80V192a32,32,0,0,0,32,32H200a32,32,0,0,0,32-32V80A32,32,0,0,0,200,48Zm16,144a16,16,0,0,1-16,16H56a16,16,0,0,1-16-16V80A16,16,0,0,1,56,64H200a16,16,0,0,1,16,16ZM72,108a12,12,0,1,1,12,12A12,12,0,0,1,72,108Zm88,0a12,12,0,1,1,12,12A12,12,0,0,1,160,108Zm4,28H92a28,28,0,0,0,0,56h72a28,28,0,0,0,0-56Zm-24,16v24H116V152ZM80,164a12,12,0,0,1,12-12h8v24H92A12,12,0,0,1,80,164Zm84,12h-8V152h8a12,12,0,0,1,0,24Z\" /></g></svg>",
  "chart": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M208,40V208H152V40Z\" opacity=\"0.16\" /><path d=\"M224,200h-8V40a8,8,0,0,0-8-8H152a8,8,0,0,0-8,8V80H96a8,8,0,0,0-8,8v40H48a8,8,0,0,0-8,8v64H32a8,8,0,0,0,0,16H224a8,8,0,0,0,0-16ZM160,48h40V200H160ZM104,96h40V200H104ZM56,144H88v56H56Z\" /></g></svg>",
  "shield": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M216,56v56c0,96-88,120-88,120S40,208,40,112V56a8,8,0,0,1,8-8H208A8,8,0,0,1,216,56Z\" opacity=\"0.16\" /><path d=\"M208,40H48A16,16,0,0,0,32,56v56c0,52.72,25.52,84.67,46.93,102.19,23.06,18.86,46,25.26,47,25.53a8,8,0,0,0,4.2,0c1-.27,23.91-6.67,47-25.53C198.48,196.67,224,164.72,224,112V56A16,16,0,0,0,208,40Zm0,72c0,37.07-13.66,67.16-40.6,89.42A129.3,129.3,0,0,1,128,223.62a128.25,128.25,0,0,1-38.92-21.81C61.82,179.51,48,149.3,48,112l0-56,160,0ZM82.34,141.66a8,8,0,0,1,11.32-11.32L112,148.69l50.34-50.35a8,8,0,0,1,11.32,11.32l-56,56a8,8,0,0,1-11.32,0Z\" /></g></svg>",
  "brain": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M240,124a48,48,0,0,1-32,45.27h0V176a40,40,0,0,1-80,0,40,40,0,0,1-80,0v-6.73h0a48,48,0,0,1,0-90.54V72a40,40,0,0,1,80,0,40,40,0,0,1,80,0v6.73A48,48,0,0,1,240,124Z\" opacity=\"0.16\" /><path d=\"M248,124a56.11,56.11,0,0,0-32-50.61V72a48,48,0,0,0-88-26.49A48,48,0,0,0,40,72v1.39a56,56,0,0,0,0,101.2V176a48,48,0,0,0,88,26.49A48,48,0,0,0,216,176v-1.41A56.09,56.09,0,0,0,248,124ZM88,208a32,32,0,0,1-31.81-28.56A55.87,55.87,0,0,0,64,180h8a8,8,0,0,0,0-16H64A40,40,0,0,1,50.67,86.27,8,8,0,0,0,56,78.73V72a32,32,0,0,1,64,0v68.26A47.8,47.8,0,0,0,88,128a8,8,0,0,0,0,16,32,32,0,0,1,0,64Zm104-44h-8a8,8,0,0,0,0,16h8a55.87,55.87,0,0,0,7.81-.56A32,32,0,1,1,168,144a8,8,0,0,0,0-16,47.8,47.8,0,0,0-32,12.26V72a32,32,0,0,1,64,0v6.73a8,8,0,0,0,5.33,7.54A40,40,0,0,1,192,164Zm16-52a8,8,0,0,1-8,8h-4a36,36,0,0,1-36-36V80a8,8,0,0,1,16,0v4a20,20,0,0,0,20,20h4A8,8,0,0,1,208,112ZM60,120H56a8,8,0,0,1,0-16h4A20,20,0,0,0,80,84V80a8,8,0,0,1,16,0v4A36,36,0,0,1,60,120Z\" /></g></svg>",
  "stack": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M224,80l-96,56L32,80l96-56Z\" opacity=\"0.16\" /><path d=\"M230.91,172A8,8,0,0,1,228,182.91l-96,56a8,8,0,0,1-8.06,0l-96-56A8,8,0,0,1,36,169.09l92,53.65,92-53.65A8,8,0,0,1,230.91,172ZM220,121.09l-92,53.65L36,121.09A8,8,0,0,0,28,134.91l96,56a8,8,0,0,0,8.06,0l96-56A8,8,0,1,0,220,121.09ZM24,80a8,8,0,0,1,4-6.91l96-56a8,8,0,0,1,8.06,0l96,56a8,8,0,0,1,0,13.82l-96,56a8,8,0,0,1-8.06,0l-96-56A8,8,0,0,1,24,80Zm23.88,0L128,126.74,208.12,80,128,33.26Z\" /></g></svg>",
  "cpu": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M200,48H56a8,8,0,0,0-8,8V200a8,8,0,0,0,8,8H200a8,8,0,0,0,8-8V56A8,8,0,0,0,200,48ZM152,152H104V104h48Z\" opacity=\"0.16\" /><path d=\"M152,96H104a8,8,0,0,0-8,8v48a8,8,0,0,0,8,8h48a8,8,0,0,0,8-8V104A8,8,0,0,0,152,96Zm-8,48H112V112h32Zm88,0H216V112h16a8,8,0,0,0,0-16H216V56a16,16,0,0,0-16-16H160V24a8,8,0,0,0-16,0V40H112V24a8,8,0,0,0-16,0V40H56A16,16,0,0,0,40,56V96H24a8,8,0,0,0,0,16H40v32H24a8,8,0,0,0,0,16H40v40a16,16,0,0,0,16,16H96v16a8,8,0,0,0,16,0V216h32v16a8,8,0,0,0,16,0V216h40a16,16,0,0,0,16-16V160h16a8,8,0,0,0,0-16Zm-32,56H56V56H200v95.87s0,.09,0,.13,0,.09,0,.13V200Z\" /></g></svg>",
  "console": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M224,56V200a8,8,0,0,1-8,8H40a8,8,0,0,1-8-8V56a8,8,0,0,1,8-8H216A8,8,0,0,1,224,56Z\" opacity=\"0.16\" /><path d=\"M216,40H40A16,16,0,0,0,24,56V200a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40Zm0,160H40V56H216V200ZM80,84A12,12,0,1,1,68,72,12,12,0,0,1,80,84Zm40,0a12,12,0,1,1-12-12A12,12,0,0,1,120,84Z\" /></g></svg>",
  "archive": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M216,96v96a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V96Z\" opacity=\"0.16\" /><path d=\"M224,48H32A16,16,0,0,0,16,64V88a16,16,0,0,0,16,16v88a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V104a16,16,0,0,0,16-16V64A16,16,0,0,0,224,48ZM208,192H48V104H208ZM224,88H32V64H224V88ZM96,136a8,8,0,0,1,8-8h48a8,8,0,0,1,0,16H104A8,8,0,0,1,96,136Z\" /></g></svg>",
  "outbox": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(3 3) scale(0.1640625)\"><path d=\"M216,48V160H179.31a8,8,0,0,0-5.66,2.34l-19.31,19.32a8,8,0,0,1-5.66,2.34H107.31a8,8,0,0,1-5.66-2.34L82.34,162.34A8,8,0,0,0,76.68,160H40V48a8,8,0,0,1,8-8H208A8,8,0,0,1,216,48Z\" opacity=\"0.16\" /><path d=\"M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32Zm0,16V152h-28.7A15.86,15.86,0,0,0,168,156.69L148.69,176H107.31L88,156.69A15.86,15.86,0,0,0,76.69,152H48V48Zm0,160H48V168H76.69L96,187.31A15.86,15.86,0,0,0,107.31,192h41.38A15.86,15.86,0,0,0,160,187.31L179.31,168H208v40ZM90.34,109.66a8,8,0,0,1,0-11.32l32-32a8,8,0,0,1,11.32,0l32,32a8,8,0,0,1-11.32,11.32L136,91.31V152a8,8,0,0,1-16,0V91.31l-18.34,18.35A8,8,0,0,1,90.34,109.66Z\" /></g></svg>"
});

// Additional official Phosphor Core duotone assets from the same pinned commit.
// https://github.com/phosphor-icons/core/tree/2b75f3ad12b420c9504ef05df8d2564a28f8500e/assets/duotone
// Geometry is unchanged; background opacity 0.16 and the outer transform match the original set.
const EXTRA_PHOSPHOR_ICONS = Object.freeze({
  "arrow-up-right": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M192,64V168L88,64Z\" opacity=\"0.16\"/><path d=\"M192,56H88a8,8,0,0,0-5.66,13.66L128.69,116,58.34,186.34a8,8,0,0,0,11.32,11.32L140,127.31l46.34,46.35A8,8,0,0,0,200,168V64A8,8,0,0,0,192,56Zm-8,92.69-38.34-38.34h0L107.31,72H184Z\"/></g></svg>",
  "warning": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M215.46,216H40.54C27.92,216,20,202.79,26.13,192.09L113.59,40.22c6.3-11,22.52-11,28.82,0l87.46,151.87C236,202.79,228.08,216,215.46,216Z\" opacity=\"0.16\"/><path d=\"M236.8,188.09,149.35,36.22h0a24.76,24.76,0,0,0-42.7,0L19.2,188.09a23.51,23.51,0,0,0,0,23.72A24.35,24.35,0,0,0,40.55,224h174.9a24.35,24.35,0,0,0,21.33-12.19A23.51,23.51,0,0,0,236.8,188.09ZM222.93,203.8a8.5,8.5,0,0,1-7.48,4.2H40.55a8.5,8.5,0,0,1-7.48-4.2,7.59,7.59,0,0,1,0-7.72L120.52,44.21a8.75,8.75,0,0,1,15,0l87.45,151.87A7.59,7.59,0,0,1,222.93,203.8ZM120,144V104a8,8,0,0,1,16,0v40a8,8,0,0,1-16,0Zm20,36a12,12,0,1,1-12-12A12,12,0,0,1,140,180Z\"/></g></svg>",
  "arrow-right": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M216,128l-72,72V56Z\" opacity=\"0.16\"/><path d=\"M221.66,122.34l-72-72A8,8,0,0,0,136,56v64H40a8,8,0,0,0,0,16h96v64a8,8,0,0,0,13.66,5.66l72-72A8,8,0,0,0,221.66,122.34ZM152,180.69V75.31L204.69,128Z\"/></g></svg>",
  "check": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M232,56V200a16,16,0,0,1-16,16H40a16,16,0,0,1-16-16V56A16,16,0,0,1,40,40H216A16,16,0,0,1,232,56Z\" opacity=\"0.16\"/><path d=\"M205.66,85.66l-96,96a8,8,0,0,1-11.32,0l-40-40a8,8,0,0,1,11.32-11.32L104,164.69l90.34-90.35a8,8,0,0,1,11.32,11.32Z\"/></g></svg>",
  "clock": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M224,128a96,96,0,1,1-96-96A96,96,0,0,1,224,128Z\" opacity=\"0.16\"/><path d=\"M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216Zm64-88a8,8,0,0,1-8,8H128a8,8,0,0,1-8-8V72a8,8,0,0,1,16,0v48h48A8,8,0,0,1,192,128Z\"/></g></svg>",
  "sign-out": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M224,56V200a16,16,0,0,1-16,16H48V40H208A16,16,0,0,1,224,56Z\" opacity=\"0.16\"/><path d=\"M120,216a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V40a8,8,0,0,1,8-8h64a8,8,0,0,1,0,16H56V208h56A8,8,0,0,1,120,216Zm109.66-93.66-40-40a8,8,0,0,0-11.32,11.32L204.69,120H112a8,8,0,0,0,0,16h92.69l-26.35,26.34a8,8,0,0,0,11.32,11.32l40-40A8,8,0,0,0,229.66,122.34Z\"/></g></svg>",
  "pencil": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M221.66,90.34,192,120,136,64l29.66-29.66a8,8,0,0,1,11.31,0L221.66,79A8,8,0,0,1,221.66,90.34Z\" opacity=\"0.16\"/><path d=\"M227.31,73.37,182.63,28.68a16,16,0,0,0-22.63,0L36.69,152A15.86,15.86,0,0,0,32,163.31V208a16,16,0,0,0,16,16H92.69A15.86,15.86,0,0,0,104,219.31L227.31,96a16,16,0,0,0,0-22.63ZM51.31,160,136,75.31,152.69,92,68,176.68ZM48,179.31,76.69,208H48Zm48,25.38L79.31,188,164,103.31,180.69,120Zm96-96L147.31,64l24-24L216,84.68Z\"/></g></svg>",
  "lock": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M216,96V208a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V96a8,8,0,0,1,8-8H208A8,8,0,0,1,216,96Z\" opacity=\"0.16\"/><path d=\"M208,80H176V56a48,48,0,0,0-96,0V80H48A16,16,0,0,0,32,96V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V96A16,16,0,0,0,208,80ZM96,56a32,32,0,0,1,64,0V80H96ZM208,208H48V96H208V208Zm-68-56a12,12,0,1,1-12-12A12,12,0,0,1,140,152Z\"/></g></svg>",
  "folder": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M128,80H32V56a8,8,0,0,1,8-8H92.69a8,8,0,0,1,5.65,2.34Z\" opacity=\"0.16\"/><path d=\"M216,72H131.31L104,44.69A15.86,15.86,0,0,0,92.69,40H40A16,16,0,0,0,24,56V200.62A15.4,15.4,0,0,0,39.38,216H216.89A15.13,15.13,0,0,0,232,200.89V88A16,16,0,0,0,216,72ZM92.69,56l16,16H40V56ZM216,200H40V88H216Z\"/></g></svg>",
  "copy": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M216,40V168H168V88H88V40Z\" opacity=\"0.16\"/><path d=\"M216,32H88a8,8,0,0,0-8,8V80H40a8,8,0,0,0-8,8V216a8,8,0,0,0,8,8H168a8,8,0,0,0,8-8V176h40a8,8,0,0,0,8-8V40A8,8,0,0,0,216,32ZM160,208H48V96H160Zm48-48H176V88a8,8,0,0,0-8-8H96V48H208Z\"/></g></svg>",
  "qr-code": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M112,56v48a8,8,0,0,1-8,8H56a8,8,0,0,1-8-8V56a8,8,0,0,1,8-8h48A8,8,0,0,1,112,56Zm-8,88H56a8,8,0,0,0-8,8v48a8,8,0,0,0,8,8h48a8,8,0,0,0,8-8V152A8,8,0,0,0,104,144Zm96-96H152a8,8,0,0,0-8,8v48a8,8,0,0,0,8,8h48a8,8,0,0,0,8-8V56A8,8,0,0,0,200,48Z\" opacity=\"0.16\"/><path d=\"M104,40H56A16,16,0,0,0,40,56v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V56A16,16,0,0,0,104,40Zm0,64H56V56h48v48Zm0,32H56a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V152A16,16,0,0,0,104,136Zm0,64H56V152h48v48ZM200,40H152a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V56A16,16,0,0,0,200,40Zm0,64H152V56h48v48Zm-64,72V144a8,8,0,0,1,16,0v32a8,8,0,0,1-16,0Zm80-16a8,8,0,0,1-8,8H184v40a8,8,0,0,1-8,8H144a8,8,0,0,1,0-16h24V144a8,8,0,0,1,16,0v8h24A8,8,0,0,1,216,160Zm0,32v16a8,8,0,0,1-16,0V192a8,8,0,0,1,16,0Z\"/></g></svg>",
  "sparkle": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M194.82,151.43l-55.09,20.3-20.3,55.09a7.92,7.92,0,0,1-14.86,0l-20.3-55.09-55.09-20.3a7.92,7.92,0,0,1,0-14.86l55.09-20.3,20.3-55.09a7.92,7.92,0,0,1,14.86,0l20.3,55.09,55.09,20.3A7.92,7.92,0,0,1,194.82,151.43Z\" opacity=\"0.16\"/><path d=\"M197.58,129.06,146,110l-19-51.62a15.92,15.92,0,0,0-29.88,0L78,110l-51.62,19a15.92,15.92,0,0,0,0,29.88L78,178l19,51.62a15.92,15.92,0,0,0,29.88,0L146,178l51.62-19a15.92,15.92,0,0,0,0-29.88ZM137,164.22a8,8,0,0,0-4.74,4.74L112,223.85,91.78,169A8,8,0,0,0,87,164.22L32.15,144,87,123.78A8,8,0,0,0,91.78,119L112,64.15,132.22,119a8,8,0,0,0,4.74,4.74L191.85,144ZM144,40a8,8,0,0,1,8-8h16V16a8,8,0,0,1,16,0V32h16a8,8,0,0,1,0,16H184V64a8,8,0,0,1-16,0V48H152A8,8,0,0,1,144,40ZM248,88a8,8,0,0,1-8,8h-8v8a8,8,0,0,1-16,0V96h-8a8,8,0,0,1,0-16h8V72a8,8,0,0,1,16,0v8h8A8,8,0,0,1,248,88Z\"/></g></svg>",
  "arrow-counter-clockwise": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M216,128a88,88,0,1,1-88-88A88,88,0,0,1,216,128Z\" opacity=\"0.16\"/><path d=\"M224,128a96,96,0,0,1-94.71,96H128A95.38,95.38,0,0,1,62.1,197.8a8,8,0,0,1,11-11.63A80,80,0,1,0,71.43,71.39a3.07,3.07,0,0,1-.26.25L44.59,96H72a8,8,0,0,1,0,16H24a8,8,0,0,1-8-8V56a8,8,0,0,1,16,0V85.8L60.25,60A96,96,0,0,1,224,128Z\"/></g></svg>",
  "trash": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M200,56V208a8,8,0,0,1-8,8H64a8,8,0,0,1-8-8V56Z\" opacity=\"0.16\"/><path d=\"M216,48H176V40a24,24,0,0,0-24-24H104A24,24,0,0,0,80,40v8H40a8,8,0,0,0,0,16h8V208a16,16,0,0,0,16,16H192a16,16,0,0,0,16-16V64h8a8,8,0,0,0,0-16ZM96,40a8,8,0,0,1,8-8h48a8,8,0,0,1,8,8v8H96Zm96,168H64V64H192ZM112,104v64a8,8,0,0,1-16,0V104a8,8,0,0,1,16,0Zm48,0v64a8,8,0,0,1-16,0V104a8,8,0,0,1,16,0Z\"/></g></svg>",
  "download": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"currentColor\" transform=\"translate(1.5 1.5) scale(0.08203125)\"><path d=\"M232,136v64a8,8,0,0,1-8,8H32a8,8,0,0,1-8-8V136a8,8,0,0,1,8-8H224A8,8,0,0,1,232,136Z\" opacity=\"0.16\"/><path d=\"M240,136v64a16,16,0,0,1-16,16H32a16,16,0,0,1-16-16V136a16,16,0,0,1,16-16H72a8,8,0,0,1,0,16H32v64H224V136H184a8,8,0,0,1,0-16h40A16,16,0,0,1,240,136Zm-117.66-2.34a8,8,0,0,0,11.32,0l48-48a8,8,0,0,0-11.32-11.32L136,108.69V24a8,8,0,0,0-16,0v84.69L85.66,74.34A8,8,0,0,0,74.34,85.66ZM200,168a12,12,0,1,0-12,12A12,12,0,0,0,200,168Z\"/></g></svg>"
});
const PHOSPHOR_ALIASES = Object.freeze({ "shield-check": "shield" });

// Original JUPITER RELAY vectors; embedded here for a standalone, editable preview.
const BRAND_ASSETS = Object.freeze({
  "orbit": "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 256 256\">\n<g fill=\"none\" stroke=\"#182847\" stroke-width=\"3.5\">\n<path d=\"M153 53C172 68 154 104 122 140S55 200 36 184S38 133 71 96S134 37 153 53Z\" fill=\"#BDEBFF\"/>\n<path d=\"M140 68C149 75 132 103 110 128S61 174 51 167S59 132 81 107S131 61 140 68Z\" fill=\"#F5F9FF\"/>\n<path d=\"M220 72C239 87 221 123 189 159S122 219 103 203S105 152 138 115S201 56 220 72Z\" fill=\"#FFD665\"/>\n<path d=\"M207 87C216 94 199 122 177 147S128 193 118 186S126 151 148 126S198 80 207 87Z\" fill=\"#F5F9FF\"/>\n<path d=\"M143 74C157 84 143 110 129 128\" stroke=\"#BDEBFF\" stroke-width=\"15\"/>\n<path d=\"M137 70C153 84 138 109 124 126M151 80C165 96 148 120 136 134\" stroke=\"#182847\" stroke-width=\"3.5\"/>\n<circle cx=\"59\" cy=\"71\" r=\"9\" fill=\"#2F55E7\"/>\n<circle cx=\"222\" cy=\"183\" r=\"7\" fill=\"#FF927A\"/>\n</g></svg>",
  "base": "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 256 256\">\n<ellipse cx=\"123\" cy=\"230\" rx=\"75\" ry=\"10\" fill=\"#D7E1EE\"/>\n<g stroke=\"#182847\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n<path d=\"M98 196V219H78C72 219 69 226 74 229H108V196Z\" fill=\"#BDEBFF\"/>\n<path d=\"M139 196V219H159C165 219 168 226 163 229H129V196Z\" fill=\"#BDEBFF\"/>\n<rect x=\"57\" y=\"130\" width=\"19\" height=\"50\" rx=\"9\" transform=\"rotate(14 57 130)\" fill=\"#FFFFFF\"/>\n<circle cx=\"51\" cy=\"185\" r=\"12\" fill=\"#FFD665\"/>\n<rect x=\"170\" y=\"126\" width=\"20\" height=\"50\" rx=\"10\" transform=\"rotate(-18 170 126)\" fill=\"#FFFFFF\"/>\n<rect x=\"70\" y=\"114\" width=\"102\" height=\"91\" rx=\"27\" fill=\"#FFFFFF\"/>\n<path d=\"M74 182C91 193 143 197 169 183V185C169 197 160 205 149 205H92C81 205 73 197 73 186Z\" fill=\"#ECF3FF\" stroke=\"none\"/>\n<rect x=\"93\" y=\"151\" width=\"56\" height=\"30\" rx=\"10\" fill=\"#FFD665\"/>\n<path d=\"M105 167L116 157L126 167L116 177ZM123 167L134 157L144 167L134 177Z\" fill=\"none\" stroke-width=\"2.5\"/>\n<path d=\"M120 44V29\"/>\n<circle cx=\"120\" cy=\"23\" r=\"8\" fill=\"#FF927A\"/>\n<rect x=\"47\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"180\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"56\" y=\"45\" width=\"128\" height=\"87\" rx=\"28\" fill=\"#FFFFFF\"/>\n<path d=\"M65 63C68 57 74 54 81 54H107\" fill=\"none\" stroke=\"#ECF3FF\" stroke-width=\"5\"/>\n<rect x=\"70\" y=\"66\" width=\"100\" height=\"49\" rx=\"18\" fill=\"#182847\"/>\n<ellipse cx=\"96\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<ellipse cx=\"144\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<path d=\"M113 100Q120 106 127 100\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"2.5\"/>\n<circle cx=\"90\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n<circle cx=\"138\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n</g></svg>",
  "navigator": "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 256 256\">\n<ellipse cx=\"123\" cy=\"230\" rx=\"75\" ry=\"10\" fill=\"#D7E1EE\"/>\n<g stroke=\"#182847\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n<path d=\"M98 196V219H78C72 219 69 226 74 229H108V196Z\" fill=\"#BDEBFF\"/>\n<path d=\"M139 196V219H159C165 219 168 226 163 229H129V196Z\" fill=\"#BDEBFF\"/>\n<rect x=\"57\" y=\"130\" width=\"19\" height=\"50\" rx=\"9\" transform=\"rotate(14 57 130)\" fill=\"#FFFFFF\"/>\n<circle cx=\"51\" cy=\"185\" r=\"12\" fill=\"#FFD665\"/>\n<rect x=\"170\" y=\"126\" width=\"20\" height=\"50\" rx=\"10\" transform=\"rotate(-18 170 126)\" fill=\"#FFFFFF\"/>\n<rect x=\"70\" y=\"114\" width=\"102\" height=\"91\" rx=\"27\" fill=\"#FFFFFF\"/>\n<path d=\"M74 182C91 193 143 197 169 183V185C169 197 160 205 149 205H92C81 205 73 197 73 186Z\" fill=\"#ECF3FF\" stroke=\"none\"/>\n<rect x=\"93\" y=\"151\" width=\"56\" height=\"30\" rx=\"10\" fill=\"#FFD665\"/>\n<path d=\"M105 167L116 157L126 167L116 177ZM123 167L134 157L144 167L134 177Z\" fill=\"none\" stroke-width=\"2.5\"/>\n<path d=\"M120 44V29\"/>\n<circle cx=\"120\" cy=\"23\" r=\"8\" fill=\"#FF927A\"/>\n<rect x=\"47\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"180\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"56\" y=\"45\" width=\"128\" height=\"87\" rx=\"28\" fill=\"#FFFFFF\"/>\n<path d=\"M65 63C68 57 74 54 81 54H107\" fill=\"none\" stroke=\"#ECF3FF\" stroke-width=\"5\"/>\n<rect x=\"70\" y=\"66\" width=\"100\" height=\"49\" rx=\"18\" fill=\"#182847\"/>\n<ellipse cx=\"96\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<ellipse cx=\"144\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<path d=\"M113 100Q120 106 127 100\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"2.5\"/>\n<circle cx=\"90\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n<circle cx=\"138\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n\n<path d=\"M58 63L86 51\" fill=\"none\" stroke=\"#2F55E7\" stroke-width=\"7\"/>\n<circle cx=\"148\" cy=\"86\" r=\"23\" fill=\"#BDEBFF\"/>\n<circle cx=\"148\" cy=\"86\" r=\"15\" fill=\"#2F55E7\"/>\n<path d=\"M141 80Q147 74 154 81\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"3\"/>\n<path d=\"M168 98L181 110\" stroke-width=\"6\"/>\n<g transform=\"rotate(10 191 174)\">\n<rect x=\"166\" y=\"137\" width=\"51\" height=\"72\" rx=\"10\" fill=\"#2F55E7\"/>\n<rect x=\"172\" y=\"145\" width=\"39\" height=\"51\" rx=\"5\" fill=\"#F5F9FF\" stroke-width=\"2\"/>\n<path d=\"M179 187V177M190 187V168M201 187V159\" stroke=\"#2F55E7\" stroke-width=\"5\"/>\n<circle cx=\"191\" cy=\"202\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n</g>\n<path d=\"M178 176Q190 175 192 184Q190 193 180 191\" fill=\"#FFD665\"/>\n</g></svg>",
  "guardian": "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 256 256\">\n<ellipse cx=\"123\" cy=\"230\" rx=\"75\" ry=\"10\" fill=\"#D7E1EE\"/>\n<g stroke=\"#182847\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n<path d=\"M98 196V219H78C72 219 69 226 74 229H108V196Z\" fill=\"#BDEBFF\"/>\n<path d=\"M139 196V219H159C165 219 168 226 163 229H129V196Z\" fill=\"#BDEBFF\"/>\n<rect x=\"57\" y=\"130\" width=\"19\" height=\"50\" rx=\"9\" transform=\"rotate(14 57 130)\" fill=\"#FFFFFF\"/>\n<circle cx=\"51\" cy=\"185\" r=\"12\" fill=\"#FFD665\"/>\n<rect x=\"170\" y=\"126\" width=\"20\" height=\"50\" rx=\"10\" transform=\"rotate(-18 170 126)\" fill=\"#FFFFFF\"/>\n<rect x=\"70\" y=\"114\" width=\"102\" height=\"91\" rx=\"27\" fill=\"#FFFFFF\"/>\n<path d=\"M74 182C91 193 143 197 169 183V185C169 197 160 205 149 205H92C81 205 73 197 73 186Z\" fill=\"#ECF3FF\" stroke=\"none\"/>\n<rect x=\"93\" y=\"151\" width=\"56\" height=\"30\" rx=\"10\" fill=\"#FFD665\"/>\n<path d=\"M105 167L116 157L126 167L116 177ZM123 167L134 157L144 167L134 177Z\" fill=\"none\" stroke-width=\"2.5\"/>\n<path d=\"M120 44V29\"/>\n<circle cx=\"120\" cy=\"23\" r=\"8\" fill=\"#FF927A\"/>\n<rect x=\"47\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"180\" y=\"79\" width=\"13\" height=\"28\" rx=\"6.5\" fill=\"#BDEBFF\"/>\n<rect x=\"56\" y=\"45\" width=\"128\" height=\"87\" rx=\"28\" fill=\"#FFFFFF\"/>\n<path d=\"M65 63C68 57 74 54 81 54H107\" fill=\"none\" stroke=\"#ECF3FF\" stroke-width=\"5\"/>\n<rect x=\"70\" y=\"66\" width=\"100\" height=\"49\" rx=\"18\" fill=\"#182847\"/>\n<ellipse cx=\"96\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<ellipse cx=\"144\" cy=\"88\" rx=\"9\" ry=\"12\" fill=\"#BDEBFF\" stroke=\"none\"/>\n<path d=\"M113 100Q120 106 127 100\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"2.5\"/>\n<circle cx=\"90\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n<circle cx=\"138\" cy=\"84\" r=\"2.5\" fill=\"#FFFFFF\" stroke=\"none\"/>\n\n<path d=\"M81 55L91 44H149L161 55\" fill=\"#BFEED9\"/>\n<path d=\"M120 39V51\" stroke-width=\"3\"/>\n<path d=\"M169 153L196 140L226 153V177C224 199 213 216 196 225C179 216 167 199 167 177Z\" fill=\"#BFEED9\"/>\n<path d=\"M177 159L196 150L216 159V177C214 193 207 207 196 214C185 207 177 193 177 177Z\" fill=\"#FFFFFF\" stroke-width=\"2.5\"/>\n<path d=\"M185 180L193 188L207 170\" fill=\"none\" stroke=\"#2F55E7\" stroke-width=\"5\"/>\n<path d=\"M175 172Q163 174 164 183Q165 191 176 189\" fill=\"#FFD665\"/>\n</g></svg>"
});

let nextControlId = 0;
const controlId = prefix => `${prefix}-${++nextControlId}`;
const sizeStyle = size => typeof size === 'number' ? `${size}px` : size;
// Normalize only the enclosing viewport/transform; official path geometry stays intact.
const ICON_SVGS = Object.freeze(Object.fromEntries(Object.entries({ ...PHOSPHOR_ICONS, ...EXTRA_PHOSPHOR_ICONS }).map(([name, svg]) => [name,
  svg.replace('viewBox="0 0 48 48"', 'viewBox="0 0 24 24"').replace('translate(3 3) scale(0.1640625)', 'translate(1.5 1.5) scale(0.08203125)'),
])));
const warnedMissingIcons = new Set();
const valueProp = { type: [String, Number], default: '' };
const fieldProps = {
  modelValue: valueProp, label: String, hint: String, error: String, id: String,
  name: String, placeholder: String, disabled: Boolean, readonly: Boolean,
  required: Boolean, autocomplete: String,
};
const describedBy = (props, id) => [props.hint && `${id}-hint`, props.error && `${id}-error`].filter(Boolean).join(' ') || undefined;
function fieldFrame(props, id, control, extra) {
  return h('div', { class: ['r-form-field', props.error && 'r-form-field--error', props.disabled && 'r-form-field--disabled'] }, [
    props.label && h('label', { class: 'r-form-field__label', for: id }, [props.label, props.required && h('span', { class: 'r-form-field__required', 'aria-hidden': 'true' }, ' *')]),
    control,
    extra,
    props.hint && h('p', { class: 'r-form-field__hint', id: `${id}-hint` }, props.hint),
    props.error && h('p', { class: 'r-form-field__error', id: `${id}-error`, role: 'alert' }, props.error),
  ]);
}

const RIcon = {
  name: 'RIcon',
  props: { name: { type: String, default: 'link' }, size: { type: [Number, String], default: 24 }, label: String },
  setup(props) {
    return () => {
      const svg = ICON_SVGS[PHOSPHOR_ALIASES[props.name] || props.name];
      if (!svg && !warnedMissingIcons.has(props.name)) {
        warnedMissingIcons.add(props.name);
        console.warn(`[JUPITER RELAY] Missing Phosphor icon: ${props.name}`);
      }
      return h('span', {
        class: ['r-icon', !svg && 'r-icon--missing'], role: props.label || !svg ? 'img' : undefined,
        'aria-label': props.label || (!svg ? `未提供图标：${props.name}` : undefined),
        'aria-hidden': props.label || !svg ? undefined : 'true',
        'data-missing-icon': !svg ? props.name : undefined,
        title: !svg ? `Missing icon: ${props.name}` : undefined,
        style: { width: sizeStyle(props.size), height: sizeStyle(props.size) },
        ...(svg ? { innerHTML: svg } : {}),
      }, svg ? undefined : '?');
    };
  },
};

const RButton = {
  name: 'RButton', inheritAttrs: false,
  props: {
    kind: { type: String, default: 'primary' }, state: { type: String, default: 'default' },
    disabled: Boolean, loading: Boolean, type: { type: String, default: 'button' },
    loadingText: { type: String, default: '处理中…' }, icon: String, block: Boolean,
  },
  emits: ['click'],
  setup(props, { emit, slots }) {
    const attrs = useAttrs();
    return () => {
      const busy = props.loading || props.state === 'loading';
      const disabled = props.disabled || busy || props.state === 'disabled';
      const kind = ['primary', 'secondary', 'text', 'danger'].includes(props.kind) ? props.kind : 'primary';
      return h('button', {
        ...attrs, type: props.type, disabled,
        class: ['r-button', `r-button--${kind}`, `r-button--state-${props.state}`, props.block && 'r-button--block', busy && 'is-loading', attrs.class],
        'aria-busy': busy || undefined,
        onClick: event => { if (!disabled) emit('click', event); },
      }, [busy ? h('span', { class: 'r-spinner', 'aria-hidden': 'true' }) : props.icon && h(RIcon, { name: props.icon, size: 22 }),
        h('span', { class: 'r-button__label' }, busy ? props.loadingText : slots.default?.())]);
    };
  },
};

const RField = {
  name: 'RField', inheritAttrs: false,
  props: { ...fieldProps, type: { type: String, default: 'text' }, minlength: [Number, String], maxlength: [Number, String], inputmode: String, pattern: String },
  emits: ['update:modelValue', 'input', 'change', 'blur', 'focus'],
  setup(props, { emit, expose }) {
    const attrs = useAttrs(); const id = props.id || controlId('r-field'); const input = ref(null); const visible = ref(false);
    expose({ focus: () => input.value?.focus(), input });
    return () => fieldFrame(props, id, h('div', { class: ['r-field-wrap', props.type === 'password' && 'r-field-wrap--password'] }, [
      h('input', {
        ...attrs, id, ref: input, class: ['r-input', attrs.class], type: props.type === 'password' && visible.value ? 'text' : props.type,
        value: props.modelValue ?? '', name: props.name, placeholder: props.placeholder,
        disabled: props.disabled, readonly: props.readonly, required: props.required,
        autocomplete: props.autocomplete, minlength: props.minlength, maxlength: props.maxlength, inputmode: props.inputmode, pattern: props.pattern,
        'aria-invalid': !!props.error || undefined, 'aria-describedby': [describedBy(props, id), attrs['aria-describedby']].filter(Boolean).join(' ') || undefined,
        onInput: e => { emit('update:modelValue', e.target.value); emit('input', e); },
        onChange: e => emit('change', e), onBlur: e => emit('blur', e), onFocus: e => emit('focus', e),
      }),
      props.type === 'password' && h('button', {
        type: 'button', class: 'r-password-toggle', disabled: props.disabled,
        'aria-label': visible.value ? '隐藏密码' : '显示密码', title: visible.value ? '隐藏密码' : '显示密码',
        'aria-controls': id, 'aria-pressed': visible.value,
        onClick: () => { visible.value = !visible.value; input.value?.focus({ preventScroll: true }); },
      }, visible.value ? '隐藏' : '显示'),
    ]));
  },
};

const RSelect = {
  name: 'RSelect', inheritAttrs: false,
  props: { ...fieldProps, options: { type: Array, default: () => [] } },
  emits: ['update:modelValue', 'change', 'blur', 'focus'],
  setup(props, { emit }) {
    const attrs = useAttrs(); const id = props.id || controlId('r-select');
    const options = computed(() => props.options.map(item => typeof item === 'object' && item !== null ? { label: item.label ?? String(item.value), value: item.value, disabled: !!item.disabled } : { label: String(item), value: item, disabled: false }));
    return () => fieldFrame(props, id, h('select', {
      ...attrs, id, class: ['r-input', 'r-select', attrs.class], name: props.name,
      value: options.value.findIndex(item => item.value === props.modelValue),
      disabled: props.disabled || props.readonly, required: props.required,
      'aria-invalid': !!props.error || undefined, 'aria-describedby': describedBy(props, id),
      onChange: e => { const selected = options.value[Number(e.target.value)]; if (selected) emit('update:modelValue', selected.value); emit('change', e); },
      onBlur: e => emit('blur', e), onFocus: e => emit('focus', e),
    }, [props.placeholder && h('option', { value: -1, disabled: true }, props.placeholder), ...options.value.map((item, index) => h('option', { value: index, disabled: item.disabled }, item.label))]));
  },
};

const RTextarea = {
  name: 'RTextarea', inheritAttrs: false,
  props: { ...fieldProps, rows: { type: [Number, String], default: 4 }, maxlength: { type: [Number, String], default: 2000 }, count: { type: Boolean, default: true } },
  emits: ['update:modelValue', 'input', 'change', 'blur', 'focus'],
  setup(props, { emit, expose }) {
    const attrs = useAttrs(); const id = props.id || controlId('r-textarea'); const input = ref(null);
    expose({ focus: () => input.value?.focus(), input });
    return () => fieldFrame(props, id, h('textarea', {
      ...attrs, ref: input, id, class: ['r-input', 'r-textarea', attrs.class], name: props.name,
      value: props.modelValue ?? '', rows: props.rows, maxlength: props.maxlength,
      placeholder: props.placeholder, disabled: props.disabled, readonly: props.readonly, required: props.required,
      'aria-invalid': !!props.error || undefined,
      'aria-describedby': [describedBy(props, id), props.count && `${id}-count`].filter(Boolean).join(' ') || undefined,
      onInput: e => { emit('update:modelValue', e.target.value); emit('input', e); },
      onChange: e => emit('change', e), onBlur: e => emit('blur', e), onFocus: e => emit('focus', e),
    }), props.count && h('p', { class: 'r-textarea-count', id: `${id}-count` }, `${String(props.modelValue ?? '').length}${props.maxlength ? ` / ${props.maxlength}` : ''} 字符`));
  },
};

const RDateTime = {
  name: 'RDateTime', inheritAttrs: false,
  props: { ...fieldProps, type: { type: String, default: 'datetime-local' }, min: String, max: String, step: [Number, String] },
  emits: ['update:modelValue', 'change', 'blur'],
  setup(props, { emit }) {
    const attrs = useAttrs(); const id = props.id || controlId('r-datetime');
    return () => fieldFrame(props, id, h('input', {
      ...attrs, id, class: ['r-input', 'r-datetime', attrs.class], type: props.type === 'date' ? 'date' : 'datetime-local',
      name: props.name, value: props.modelValue ?? '', min: props.min, max: props.max, step: props.step,
      disabled: props.disabled, readonly: props.readonly, required: props.required,
      'aria-invalid': !!props.error || undefined, 'aria-describedby': describedBy(props, id),
      onInput: e => emit('update:modelValue', e.target.value), onChange: e => emit('change', e), onBlur: e => emit('blur', e),
    }));
  },
};

const RIconButton = {
  name: 'RIconButton', inheritAttrs: false,
  props: { label: { type: String, required: true }, title: String, icon: { type: String, default: 'link' }, disabled: Boolean, loading: Boolean, kind: { type: String, default: 'text' } },
  emits: ['click'],
  setup(props, { emit }) {
    const attrs = useAttrs();
    return () => h('button', {
      ...attrs, type: 'button', class: ['r-icon-button', `r-icon-button--${props.kind}`, attrs.class],
      'aria-label': props.label, title: props.title || props.label,
      'aria-busy': props.loading || undefined, disabled: props.disabled || props.loading,
      onClick: event => { if (!props.disabled && !props.loading) emit('click', event); },
    }, [props.loading ? h('span', { class: 'r-spinner', 'aria-hidden': 'true' }) : h(RIcon, { name: props.icon })]);
  },
};

const RCheckbox = {
  name: 'RCheckbox', inheritAttrs: false,
  props: { modelValue: Boolean, disabled: Boolean, label: String, id: String, name: String },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit, slots }) {
    const attrs = useAttrs(); const id = props.id || controlId('r-checkbox');
    return () => h('label', { class: ['r-checkbox', props.disabled && 'r-checkbox--disabled'], for: id }, [
      h('input', { ...attrs, id, class: ['r-checkbox__input', attrs.class], type: 'checkbox', name: props.name,
        checked: props.modelValue, disabled: props.disabled,
        onChange: event => { emit('update:modelValue', event.target.checked); emit('change', event); } }),
      h('span', { class: 'r-checkbox__box', 'aria-hidden': 'true' }, [h('svg', { viewBox: '0 0 20 20', fill: 'none' }, [h('path', { d: 'M4.5 10 8 13.5 15.5 6', stroke: 'currentColor', 'stroke-width': 2.2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })])]),
      h('span', { class: 'r-checkbox__label' }, slots.default?.() || props.label),
    ]);
  },
};

const RBadge = {
  name: 'RBadge',
  props: { tone: { type: String, default: 'info' }, icon: String },
  setup(props, { slots }) {
    return () => h('span', { class: ['r-badge', `r-badge--${['success', 'warning', 'danger', 'info', 'unknown'].includes(props.tone) ? props.tone : 'info'}`] }, [props.icon && h(RIcon, { name: props.icon, size: 17 }), slots.default?.()]);
  },
};

const SMALL_MARK = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" fill="none"><g stroke="currentColor" stroke-width="4" stroke-linecap="round"><ellipse cx="25" cy="29" rx="10" ry="19" transform="rotate(40 25 29)"/><ellipse cx="40" cy="36" rx="10" ry="19" transform="rotate(40 40 36)"/><path d="M29 16c7 5 5 14-1 21" stroke="var(--surface, #fff)" stroke-width="8"/><path d="M27 14c9 6 7 17 0 24"/></g></svg>';
const RBrand = {
  name: 'RBrand',
  props: { variant: { type: String, default: 'color' }, size: { type: [Number, String], default: 48 }, showText: Boolean, label: { type: String, default: 'JUPITER RELAY · 木星中继站' } },
  setup(props) {
    return () => h('span', { class: ['r-brand', `r-brand--${props.variant}`], role: 'img', 'aria-label': props.label }, [
      h('span', { class: 'r-brand__mark', 'aria-hidden': 'true', style: { width: sizeStyle(props.size), height: sizeStyle(props.size) }, innerHTML: props.variant === 'color' ? BRAND_ASSETS.orbit : SMALL_MARK }),
      props.showText && h('span', { class: 'r-brand__wordmark', 'aria-hidden': 'true' }, [h('strong', 'JUPITER RELAY'), h('span', '木星中继站')]),
    ]);
  },
};

const FACE_ART = Object.freeze({
  neutral: '<g data-expression="neutral"><ellipse cx="96" cy="88" rx="9" ry="12" fill="#BDEBFF" stroke="none"/><ellipse cx="144" cy="88" rx="9" ry="12" fill="#BDEBFF" stroke="none"/><path d="M113 100Q120 106 127 100" fill="none" stroke="#FFFFFF" stroke-width="2.5"/><circle cx="90" cy="84" r="2.5" fill="#FFFFFF" stroke="none"/><circle cx="138" cy="84" r="2.5" fill="#FFFFFF" stroke="none"/></g>',
  waiting: '<g data-expression="waiting" fill="none" stroke="#BDEBFF" stroke-width="4" stroke-linecap="round"><path d="M86 89h18M134 89h18"/><path d="M114 102h12" stroke="#FFFFFF" stroke-width="2.5"/><circle cx="91" cy="88" r="1.5" fill="#FFFFFF" stroke="none"/></g>',
  success: '<g data-expression="success" fill="none" stroke="#BDEBFF" stroke-width="4" stroke-linecap="round"><path d="M87 91q9-14 18 0M135 91q9-14 18 0"/><path d="M109 98q11 15 22 0" stroke="#FFFFFF" stroke-width="2.5"/><path d="M79 99h5M156 99h5" stroke="#FF927A" stroke-width="3"/></g>',
  recovery: '<g data-expression="recovery" fill="none" stroke="#BDEBFF" stroke-width="4" stroke-linecap="round"><path d="M88 91q8-5 16 0M136 91q8-5 16 0"/><path d="M89 77l13-3M136 74l13 3" stroke-width="2.5"/><path d="M113 103q7-4 14 0" stroke="#FFFFFF" stroke-width="2.5"/></g>',
});
function robotMarkup(role, expression) {
  const base = BRAND_ASSETS[role] || BRAND_ASSETS.base;
  const face = FACE_ART[expression] || FACE_ART.neutral;
  return base
    .replace(/<ellipse cx="(?:96|144)" cy="88"[^>]*\/>/g, '')
    .replace(/<path d="M113 100Q120 106 127 100"[^>]*\/>/g, '')
    .replace(/<circle cx="(?:90|138)" cy="84"[^>]*\/>/g, '')
    .replace('<rect x="70" y="66" width="100" height="49" rx="18" fill="#182847"/>', '$&' + face);
}
const RRobot = {
  name: 'RRobot',
  props: { role: { type: String, default: 'base' }, expression: { type: String, default: 'neutral' }, size: { type: [Number, String], default: 160 }, label: String },
  setup(props) {
    const markup = computed(() => robotMarkup(['base', 'navigator', 'guardian'].includes(props.role) ? props.role : 'base', props.expression));
    return () => h('span', {
      class: ['r-robot', `r-robot--${props.role}`, `r-robot--${props.expression}`],
      style: { width: sizeStyle(props.size), height: sizeStyle(props.size) },
      role: props.label ? 'img' : undefined, 'aria-label': props.label, 'aria-hidden': props.label ? undefined : 'true',
      innerHTML: markup.value,
    });
  },
};

const openDialogs = new Set();
let savedBodyOverflow = '';
function lockPage(dialog) {
  if (openDialogs.has(dialog)) return;
  if (!openDialogs.size) { savedBodyOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; }
  openDialogs.add(dialog);
}
function unlockPage(dialog) {
  if (!openDialogs.delete(dialog)) return;
  if (!openDialogs.size) document.body.style.overflow = savedBodyOverflow;
}
const RModal = {
  name: 'RModal', inheritAttrs: false,
  props: { open: Boolean, title: { type: String, default: '详情' }, drawer: Boolean, closeOnBackdrop: { type: Boolean, default: true }, description: String, width: [String, Number] },
  emits: ['close'],
  setup(props, { emit, slots, expose }) {
    const attrs = useAttrs(); const element = ref(null); const headingId = controlId('r-modal-title');
    let restoreTarget = null; let closeRequested = false; let backdropPressed = false; let destroyed = false;
    function restoreFocus() {
      const target = restoreTarget; restoreTarget = null;
      nextTick(() => {
        if (!target?.isConnected || typeof target.focus !== 'function') return;
        if (openDialogs.size && ![...openDialogs].some(dialog => dialog.contains(target))) return;
        target.focus({ preventScroll: true });
      });
    }
    function requestClose(reason) {
      if (closeRequested) return;
      closeRequested = true; emit('close', reason);
      // A controlled parent may temporarily decline dismissal during submission.
      // Keep a still-open dialog dismissible after that guard is lifted.
      nextTick(() => { if (!destroyed && props.open) closeRequested = false; });
    }
    function closeNative() {
      if (element.value?.open) element.value.close();
      unlockPage(element.value); restoreFocus();
    }
    async function syncOpen() {
      await nextTick(); if (destroyed || !element.value) return;
      if (props.open) {
        if (!element.value.open) {
          restoreTarget = document.activeElement; closeRequested = false;
          element.value.showModal(); lockPage(element.value);
        }
      } else closeNative();
    }
    function outside(event) {
      if (event.target !== element.value) return false;
      const r = element.value.getBoundingClientRect();
      return event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom;
    }
    watch(() => props.open, syncOpen, { flush: 'post' });
    onMounted(syncOpen);
    onBeforeUnmount(() => { destroyed = true; closeNative(); });
    expose({ focus: () => element.value?.focus(), dialog: element });
    return () => h('dialog', {
      ...attrs, ref: element, class: ['r-modal', props.drawer && 'r-modal--drawer', attrs.class],
      style: [{ '--r-modal-width': props.width ? sizeStyle(props.width) : props.drawer ? '620px' : '620px' }, attrs.style],
      'aria-labelledby': headingId, 'aria-modal': 'true', 'aria-describedby': props.description ? `${headingId}-description` : undefined,
      onCancel: event => { event.preventDefault(); requestClose('escape'); },
      onClose: () => {
        // close() queues this event. A new dialog may already be open by arrival.
        // The stale event must not dismiss it or release its focus/scroll lock.
        if (element.value?.open) return;
        unlockPage(element.value); restoreFocus();
        if (props.open && !destroyed) requestClose('native');
      },
      onPointerdown: event => { backdropPressed = outside(event); },
      onClick: event => { if (props.closeOnBackdrop && backdropPressed && outside(event)) requestClose('backdrop'); backdropPressed = false; },
    }, [h('div', { class: 'r-modal__panel' }, [
      h('header', { class: 'r-modal__header' }, [h('div', [h('h2', { id: headingId, class: 'r-modal__title' }, props.title), props.description && h('p', { id: `${headingId}-description`, class: 'r-modal__description' }, props.description)]),
        h('button', { type: 'button', class: 'r-modal__close', 'aria-label': '关闭' + props.title, title: '关闭', onClick: () => requestClose('button') }, [h('span', { 'aria-hidden': 'true' }, '关闭')])]),
      h('div', { class: 'r-modal__body' }, slots.default?.()),
      slots.footer && h('footer', { class: 'r-modal__footer' }, slots.footer()),
    ])]);
  },
};

export { RButton, RField, RSelect, RTextarea, RDateTime, RIconButton, RCheckbox, RBadge, RIcon, RBrand, RRobot, RModal };
export default { RButton, RField, RSelect, RTextarea, RDateTime, RIconButton, RCheckbox, RBadge, RIcon, RBrand, RRobot, RModal };
