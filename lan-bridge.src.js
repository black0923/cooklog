// 打包成 lan-bridge.js，把原生插件挂到 window 上
// （Capacitor 8 不再自动填充 Capacitor.Plugins，必须显式 registerPlugin）
import { Capacitor, registerPlugin } from '@capacitor/core';

window.LanShare = registerPlugin('LanShare');
window.Backup = registerPlugin('Backup');
window.Ai = registerPlugin('Ai');
window.Tts = registerPlugin('Tts');

function hasPlugin(name) {
  try {
    if (!Capacitor.isNativePlatform()) return false;
    var h = Capacitor.PluginHeaders;
    if (!h || !h.length) return false;
    for (var i = 0; i < h.length; i++) {
      if (h[i] && h[i].name === name) return true;
    }
    return false;
  } catch (e) {
    return false;
  }
}

// 原生侧没有注册这个插件时（旧安装包），这里返回 false，界面据此提示升级
window.LanShareAvailable = function () {
  return hasPlugin('LanShare');
};
window.BackupAvailable = function () {
  return hasPlugin('Backup');
};
window.AiAvailable = function () {
  return hasPlugin('Ai');
};
window.TtsAvailable = function () {
  return hasPlugin('Tts');
};
