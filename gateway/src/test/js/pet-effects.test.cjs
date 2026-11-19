const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function scene(reduced = false) {
  const timers = new Map(); let timerId = 0;
  let document;
  class Element {
    constructor() {
      this.style = {setProperty(){}}; this.children = []; this.queries = new Map(); this.classes = new Set();
      this.classList = {add:name => this.classes.add(name)}; this.isConnected = true;
    }
    setAttribute() {}
    querySelector(selector) { if (!this.queries.has(selector)) this.queries.set(selector,new Element()); return this.queries.get(selector); }
    appendChild(child) { this.children.push(child); }
    remove() { this.isConnected = false; }
    focus() { document.activeElement = this; }
  }
  const app = new Element(); app.inert = false;
  const previous = new Element(); const body = new Element(); body.style.overflow = 'auto';
  document = {body,activeElement:previous,createElement:() => new Element(),querySelector:() => app};
  const window = {matchMedia:() => ({matches:reduced}),addEventListener(){}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/assets/pet-effects.js'),'utf8'), {
    window, document, setTimeout:(fn,delay) => { timers.set(++timerId,{fn,delay}); return timerId; },clearTimeout:id => timers.delete(id)
  });
  const advance = ms => {
    for (const [id,timer] of [...timers].sort((a,b) => a[1].delay-b[1].delay)) {
      if (timer.delay <= ms && timers.has(id)) { timers.delete(id); timer.fn(); }
    }
  };
  return {window,app,body,previous,document,timers,advance};
}

test('summon freezes, shatters and restores page interaction automatically', async () => {
  const s = scene(); const done = s.window.petEffects.summon(); const overlay = s.body.children[0];
  assert.equal(s.app.inert,true); assert.equal(s.body.style.overflow,'hidden');
  assert.equal(overlay.querySelector('.ice-fragments').children.length,24);
  s.advance(1600); assert.ok(overlay.classes.has('is-frozen'));
  s.advance(3300); assert.ok(overlay.classes.has('is-shattering'));
  s.advance(5500); await done;
  assert.equal(s.app.inert,false); assert.equal(s.body.style.overflow,'auto');
  assert.equal(overlay.isConnected,false); assert.equal(s.document.activeElement,s.previous);
  assert.equal(s.timers.size,0);
});

test('skip and navigation cancellation always clean up the overlay', async () => {
  const s = scene(); const done = s.window.petEffects.summon();
  s.body.children[0].querySelector('[data-skip]').onclick(); await done;
  assert.equal(s.app.inert,false); assert.equal(s.timers.size,0);
  const next = s.window.petEffects.summon(); s.window.petEffects.stop(); s.window.petEffects.stop(); await next;
  assert.equal(s.app.inert,false); assert.equal(s.body.style.overflow,'auto');
});

test('reduced motion omits shards and finishes without freezing', async () => {
  const s = scene(true); const done = s.window.petEffects.summon(); const overlay = s.body.children[0];
  assert.equal(overlay.querySelector('.ice-fragments').children.length,0);
  s.advance(2300); await done;
  assert.equal(overlay.classes.size,0); assert.equal(s.app.inert,false);
});
