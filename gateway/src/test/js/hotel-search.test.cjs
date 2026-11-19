const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function scene() {
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.js'), 'utf8');
  const block = source.slice(source.indexOf('  const rows = '), source.indexOf('  async function petView()'));
  const escape = source.split('\n').find(line => line.startsWith('const escapeHtml = '));
  class Element {
    constructor() { this.innerHTML = ''; this.textContent = ''; this.value = ''; this.isConnected = true; this.children = []; this.disabled = false; }
    setAttribute() {}
    appendChild(child) { this.children.push(child); }
    querySelectorAll() { return this.controls ||= [new Element(),new Element()]; }
  }
  const elements = new Map();
  const get = key => { if (!elements.has(key)) elements.set(key,new Element()); return elements.get(key); };
  const buttons = [new Element(),new Element()];
  const calls = [];
  const context = vm.createContext({
    document:{querySelector:get,querySelectorAll:selector => selector === '#search button' ? buttons : [],createElement:() => new Element()},
    api:url => new Promise((resolve,reject) => calls.push({url,resolve,reject})),
    URLSearchParams,content:new Element(),message:new Element()
  });
  vm.runInContext(`${escape}\nlet hotelQuery='';let hotelPage=0;let hotelSearchRequest=0;let viewVersion=0;\n${block}\nglobalThis.ui={loadHotels,hotelView,leave:()=>{viewVersion++;}};`,context);
  return {ui:context.ui,elements,get,calls,buttons,message:context.message};
}
function result(name,page=0,total=1) { return {items:[{id:1,name,address:'上海',phone:'02100001111'}],page,size:10,total,source:'elasticsearch'}; }

test('hotel search sends encoded server queries and pagination keeps the submitted keyword', async () => {
  const s = scene();
  const first = s.ui.loadHotels('上海 & 云栖',0);
  const request = new URL(s.calls[0].url,'http://localhost');
  assert.equal(request.pathname,'/hotel/search');
  assert.equal(request.searchParams.get('keyword'),'上海 & 云栖');
  assert.equal(request.searchParams.get('page'),'0');
  s.calls[0].resolve(result('<酒店>',0,11));await first;
  assert.match(s.get('#results').innerHTML,/&lt;酒店&gt;/);
  const pager = s.get('#results').children.at(-1);
  s.get('#keyword').value = '未提交的输入';
  const next = pager.controls[1].onclick();
  const nextRequest = new URL(s.calls[1].url,'http://localhost');
  assert.equal(nextRequest.searchParams.get('keyword'),'上海 & 云栖');
  assert.equal(nextRequest.searchParams.get('page'),'1');
  s.calls[1].resolve(result('第二页',1,11));await next;
  assert.equal(s.get('#matches').textContent,11);
  assert.match(s.get('#results').children.at(-1).innerHTML,/第 2 \/ 2 页/);
});

test('a slower previous hotel search cannot replace newer results', async () => {
  const s = scene();
  const older = s.ui.loadHotels('旧关键词',0);
  const newer = s.ui.loadHotels('新关键词',0);
  s.calls[1].resolve(result('最新酒店'));await newer;
  s.calls[0].resolve(result('过期酒店'));await older;
  assert.match(s.get('#results').innerHTML,/最新酒店/);
  assert.doesNotMatch(s.get('#results').innerHTML,/过期酒店/);
  assert.ok(s.buttons.every(button => !button.disabled));
});

test('leaving the hotel view prevents pending search responses from rendering into another page', async () => {
  const s = scene();
  const pending = s.ui.loadHotels('上海',0);
  s.ui.leave();s.get('#results').isConnected = false;
  s.calls[0].resolve(result('迟到的结果'));await pending;
  assert.doesNotMatch(s.get('#results').innerHTML,/迟到的结果/);
});
