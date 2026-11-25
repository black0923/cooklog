const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const files = ['index.html', 'icons-data.js', 'qrcode.js', 'lan.html'];

files.forEach(function (f) {
  fs.copyFileSync(path.join(root, f), path.join(root, 'www', f));
  console.log('synced ' + f);
});
console.log('Web assets synced to www/');
