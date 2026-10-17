// 优先使用显式工具路径及PATH；仅Windows缺PATH时查已知用户安装位置。
const fs = require('node:fs');
const path = require('node:path');
function dockerExecutable() {
  if (process.env.DOCKER_BIN) return process.env.DOCKER_BIN;
  if (process.platform === 'win32' && process.env.LOCALAPPDATA) {
    const local = path.join(
      process.env.LOCALAPPDATA,
      'Programs/DockerDesktop/resources/bin/docker.exe',
    );
    if (fs.existsSync(local)) return local;
  }
  return 'docker';
}
module.exports = { dockerExecutable };
