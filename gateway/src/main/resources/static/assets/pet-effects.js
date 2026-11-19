'use strict';
(() => {
  let context;
  let master;
  let muted = false;
  let stopEffect;
  let audioNodes = [];
  const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function prepare() {
    if (reducedMotion()) return;
    try {
      const Audio = window.AudioContext || window.webkitAudioContext;
      if (!Audio) return;
      if (!context) {
        context = new Audio(); master = context.createGain();
        master.gain.value = muted ? 0 : 0.25; master.connect(context.destination);
      }
      if (context.state === 'suspended') context.resume().catch(() => {});
    } catch { /* Visual effects remain available when audio is blocked. */ }
  }
  function sound(crack = false) {
    if (!context || context.state !== 'running' || muted) return;
    const duration = crack ? 0.9 : 1.8;
    const time = context.currentTime;
    const buffer = context.createBuffer(1, Math.ceil(context.sampleRate * duration), context.sampleRate);
    const samples = buffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) samples[i] = Math.random() * 2 - 1;
    const noise = context.createBufferSource(); noise.buffer = buffer;
    const filter = context.createBiquadFilter(); filter.type = crack ? 'highpass' : 'lowpass';
    filter.frequency.setValueAtTime(crack ? 2200 : 420, time);
    if (!crack) filter.frequency.exponentialRampToValueAtTime(100, time + duration);
    const gain = context.createGain(); gain.gain.setValueAtTime(0.001, time);
    gain.gain.exponentialRampToValueAtTime(crack ? 0.7 : 1, time + 0.1);
    gain.gain.exponentialRampToValueAtTime(0.001, time + duration);
    noise.connect(filter); filter.connect(gain); gain.connect(master);
    audioNodes.push(noise, filter, gain); noise.start(time); noise.stop(time + duration);
    if (!crack) {
      const voice = context.createOscillator(); voice.type = 'sawtooth';
      voice.frequency.setValueAtTime(75, time); voice.frequency.exponentialRampToValueAtTime(30, time + duration);
      const voiceGain = context.createGain(); voiceGain.gain.setValueAtTime(0.09, time);
      voiceGain.gain.exponentialRampToValueAtTime(0.001, time + duration);
      voice.connect(voiceGain); voiceGain.connect(master); voice.start(time); voice.stop(time + duration);
      audioNodes.push(voice, voiceGain);
    }
  }
  function summon() {
    stopEffect?.();
    const reduced = reducedMotion();
    const previousFocus = document.activeElement;
    const app = document.querySelector('#app');
    const previousInert = app?.inert;
    const previousOverflow = document.body.style.overflow;
    const overlay = document.createElement('div'); overlay.className = 'summon-effect' + (reduced ? ' reduced-summon' : '');
    overlay.setAttribute('role','dialog'); overlay.setAttribute('aria-modal','true');
    overlay.setAttribute('aria-labelledby','summon-title');
    overlay.innerHTML = '<div class="summon-intro"><span class="summon-rank">SSS · 隐藏变异</span><h2 id="summon-title">冰霜巨龙 · 觉醒</h2><img src="/assets/pets/frost-dragon.svg" alt="冰霜巨龙张口怒吼，冰晶双翼展开"><p>龙吟破空，霜封万境</p></div><div class="ice-wave" aria-hidden="true"></div><div class="ice-fragments" aria-hidden="true"></div><div class="summon-controls"><button class="secondary" data-sound>关闭音效</button><button data-skip>跳过特效</button></div>';
    const fragments = overlay.querySelector('.ice-fragments');
    if (!reduced) {
      for (let row = 0; row < 3; row++) for (let col = 0; col < 4; col++) for (let half = 0; half < 2; half++) {
        const x = col * 25, y = row * 100 / 3, right = x + 25, bottom = y + 100 / 3;
        const shard = document.createElement('i'); shard.className = 'ice-shard';
        shard.style.clipPath = half ? `polygon(${x}% ${y}%,${right}% ${bottom}%,${x}% ${bottom}%)` : `polygon(${x}% ${y}%,${right}% ${y}%,${right}% ${bottom}%)`;
        shard.style.setProperty('--shard-x', `${(col - 1.5) * 48 + (half ? -8 : 8)}vw`);
        shard.style.setProperty('--shard-y', `${(row - 1) * 70 + 32}vh`);
        shard.style.setProperty('--shard-r', `${(col + row + half) % 2 ? 24 : -28}deg`);
        shard.style.setProperty('--shard-delay', `${(row + col + half) * 25}ms`);
        fragments.appendChild(shard);
      }
    }
    document.body.appendChild(overlay); document.body.style.overflow = 'hidden'; if (app) app.inert = true;
    const skip = overlay.querySelector('[data-skip]');
    const soundButton = overlay.querySelector('[data-sound]');
    const updateSound = () => { soundButton.textContent = muted ? '开启音效' : '关闭音效'; soundButton.setAttribute('aria-pressed', String(muted)); };
    updateSound(); soundButton.hidden = reduced;
    let finished = false;
    const timers = [];
    let resolveEffect;
    const completed = new Promise(resolve => { resolveEffect = resolve; });
    const finish = () => {
      if (finished) return; finished = true;
      timers.forEach(clearTimeout);
      audioNodes.forEach(node => { try { node.stop?.(); } catch {} try { node.disconnect(); } catch {} }); audioNodes = [];
      overlay.remove(); document.body.style.overflow = previousOverflow; if (app) app.inert = previousInert;
      if (previousFocus?.isConnected) previousFocus.focus();
      if (stopEffect === finish) stopEffect = undefined;
      resolveEffect();
    };
    const later = (callback, delay) => timers.push(setTimeout(() => {
      if (!finished) { try { callback(); } catch { finish(); } }
    }, delay));
    stopEffect = finish; skip.onclick = finish;
    soundButton.onclick = () => {
      muted = !muted; updateSound(); prepare();
      if (master) master.gain.setValueAtTime(muted ? 0 : 0.25, context.currentTime);
    };
    overlay.onkeydown = event => {
      if (event.key === 'Escape') finish();
      if (event.key === 'Tab') {
        event.preventDefault(); (document.activeElement === skip && !reduced ? soundButton : skip).focus();
      }
    };
    skip.focus();
    if (reduced) later(finish, 2200);
    else {
      later(() => sound(), 300);
      later(() => overlay.classList.add('is-frozen'), 1100);
      later(() => { overlay.classList.add('is-shattering'); sound(true); }, 3200);
      later(finish, 5400);
    }
    return completed;
  }
  window.petEffects = {prepare, summon, stop:() => stopEffect?.()};
  window.addEventListener('pagehide', () => stopEffect?.());
})();
