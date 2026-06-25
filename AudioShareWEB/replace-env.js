const fs = require("fs");
const path = require("path");

const proxyUrl = process.env.PROXY_URL || "https://proxy-audio-share.onrender.com";
const dist = path.join(__dirname, "dist");

if (!fs.existsSync(dist)) fs.mkdirSync(dist, { recursive: true });

// Process index.html — replace __PROXY_URL__ placeholder
const html = fs
  .readFileSync(path.join(__dirname, "index.html"), "utf-8")
  .replace(/__PROXY_URL__/g, proxyUrl);
fs.writeFileSync(path.join(dist, "index.html"), html);

// Copy remaining static files
["manifest.json", "service-worker.js", "audio-processor.js", "favicon.ico"].forEach(
  (f) => {
    const src = path.join(__dirname, f);
    if (fs.existsSync(src))
      fs.copyFileSync(src, path.join(dist, f));
  }
);

// Copy icons directory
const iconsSrc = path.join(__dirname, "icons");
const iconsDst = path.join(dist, "icons");
if (fs.existsSync(iconsSrc)) {
  if (!fs.existsSync(iconsDst)) fs.mkdirSync(iconsDst, { recursive: true });
  fs.readdirSync(iconsSrc).forEach((f) =>
    fs.copyFileSync(path.join(iconsSrc, f), path.join(iconsDst, f))
  );
}
