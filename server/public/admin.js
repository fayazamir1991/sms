'use strict';
const $=id=>document.getElementById(id);
const state={csrf:'',table:'catalog',page:1,rows:[],hasMore:false,days:30,loadId:0,reportCache:new Map()};
const titles={webpages:['صفحات منابع وب','لینک‌های کشف‌شده و ادامهٔ بررسی شب‌های بعد.'],imports:['جمع‌آوری پیام‌ها','سابقهٔ واردکردن گنجور و اجرای شبانه؛ خطاها و تعداد پیام افزوده‌شده.'],catalog:['بانک پیام‌ها','متن‌های پیشنهادی را ببین و ویرایش کن.'],installations:['نصب‌ها','هر نصب پس از نخستین اتصال به سرور ثبت می‌شود.'],queue:['صف و وضعیت پیام‌ها','آخرین وضعیت دریافت‌شده از گوشی کاربران.'],events:['روند پیام‌ها','تغییر وضعیت‌ها، حتی پس از همگام‌سازی با تأخیر.'],sent:['گزارش ارسال','متن، مخاطب و شناسهٔ نصب ارسال‌کننده.']};
const labels={url:'آدرس صفحه',fetched_at:'آخرین بررسی',done:'بررسی‌شده',job:'جاب',run_key:'دورهٔ اجرا',started_at:'شروع',finished_at:'پایان',added:'پیام افزوده‌شده',detail:'جزئیات',source:'منبع',source_url:'پیوند منبع',author:'پدیدآورنده',language:'زبان',rights:'مجوز و وضعیت اثر',id:'شناسه',body:'متن پیام',category:'دسته‌بندی',active:'فعال',created_at:'زمان ثبت',device_hash:'شناسهٔ دستگاه',app_version:'نسخهٔ اپ',enabled:'فعال',last_seen_at:'آخرین اتصال',installation_id:'شناسهٔ نصب',recipient:'شمارهٔ مخاطب',status:'وضعیت',sim_id:'شناسهٔ سیم‌کارت',revision:'نسخهٔ رکورد',approved_at:'افزودن به صف',planned_at:'زمان برنامه‌ریزی‌شده',submitted_at:'زمان درخواست ارسال',updated_at:'آخرین همگام‌سازی',message_id:'شناسهٔ پیام',occurred_at:'زمان رخداد',sent_at:'زمان ارسال',received_at:'زمان دریافت گزارش'};
const statuses={APPROVED:'در صف',SCHEDULED:'زمان‌بندی‌شده',IMMEDIATE:'آمادهٔ ارسال فوری',SENDING:'در حال ارسال',SENT:'ارسال به شبکه تأیید شد',FAILED:'ناموفق',UNCERTAIN:'نتیجه نامشخص',CANCELLED:'لغوشده'};
const columns={webpages:['source','url','fetched_at','done'],imports:['job','run_key','status','started_at','finished_at','added','detail'],catalog:['body','category','active','author','source','source_url','language','rights','created_at'],installations:['id','device_hash','app_version','enabled','created_at','last_seen_at'],queue:['body','recipient','status','installation_id','sim_id','approved_at','planned_at','submitted_at','updated_at'],events:['message_id','installation_id','status','revision','occurred_at'],sent:['body','recipient','installation_id','message_id','status','sent_at','received_at']};
const date=value=>value?new Intl.DateTimeFormat('fa-IR',{timeZone:'Asia/Tehran',dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'—';
const digits=value=>new Intl.NumberFormat('fa-IR').format(value);
async function api(path,options={}){
 const response=await fetch(path,{...options,credentials:'same-origin',headers:{...(options.body?{'Content-Type':'application/json','X-CSRF':state.csrf}:{}),...options.headers}});
 const data=await response.json();if(!response.ok){if(response.status===401)showLogin();throw new Error(data.error||'دریافت اطلاعات انجام نشد.');}return data;
}
function showLogin(){$('login').hidden=false;$('dashboard').hidden=true;state.csrf='';}
async function openDashboard(){
 $('login').hidden=true;$('dashboard').hidden=false;$('password').value='';
 await load();
}
async function load(){
 const loadId=++state.loadId,table=state.table,page=state.page,key=table+':'+state.days;
 $('notice').textContent='';document.body.setAttribute('aria-busy','true');$('connection').textContent='در حال دریافت…';
 try{
  const cached=state.reportCache.get(key);
  const [stats,data,report]=await Promise.all([api('/api/admin/stats'),api('/api/admin/tables/'+table+'?page='+page),cached&&Date.now()-cached.time<30000?Promise.resolve(cached.data):api('/api/admin/reports?table='+table+'&days='+state.days)]);
  if(loadId!==state.loadId)return;
  state.reportCache.set(key,{time:Date.now(),data:report});
  for(const k of ['installations','devices','catalog','queue','sent'])$('count-'+k).textContent=digits(stats[k]);
  state.rows=data.rows;state.hasMore=data.has_more;renderTable();renderReports(report);$('connection').textContent='متصل';
 }catch(e){if(loadId===state.loadId){$('notice').textContent=e.message;$('connection').textContent='نیاز به بررسی';}}
 finally{if(loadId===state.loadId)document.body.removeAttribute('aria-busy');}
}
const chartColors=['#8172b5','#f4e89c','#b7a9d5','#62528e','#c7b75f','#716b7e'];
function element(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
function svgElement(tag,attrs={}){const node=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [key,value]of Object.entries(attrs))node.setAttribute(key,String(value));return node;}
function dataTable(rows,firstTitle='عنوان'){
 const details=element('details'),summary=element('summary','نمایش جدول داده‌ها'),wrap=element('div',undefined,'chart-data'),table=element('table');
 const head=element('tr');head.append(element('th',firstTitle),element('th','تعداد'));table.append(head);
 for(const row of rows){const tr=element('tr');tr.append(element('td',row.label),element('td',digits(row.value)));table.append(tr);}
 wrap.append(table);details.append(summary,wrap);return details;
}
function barChart(group){
 const card=element('article',undefined,'chart-card');card.append(element('h3',group.title));
 const total=group.rows.reduce((sum,r)=>sum+r.value,0);card.append(element('p','مجموع: '+digits(total),'chart-subtitle'));
 if(!group.rows.length){card.append(element('div','هنوز داده‌ای ثبت نشده است.','empty-chart'));return card;}
 const sorted=[...group.rows].sort((a,b)=>b.value-a.value),visible=sorted.slice(0,10);
 if(sorted.length>10)visible.push({label:'سایر',value:sorted.slice(10).reduce((sum,r)=>sum+r.value,0)});
 const max=Math.max(1,...visible.map(r=>r.value));
 visible.forEach((row,i)=>{const line=element('div',undefined,'chart-row'),label=element('div',undefined,'chart-row-label');label.append(element('span',row.label),element('strong',digits(row.value)));
  const svg=svgElement('svg',{viewBox:'0 0 1000 12',role:'img','aria-label':row.label+': '+digits(row.value)}),width=Math.max(0,Math.round(row.value/max*1000));
  svg.append(svgElement('rect',{x:0,y:0,width:1000,height:12,rx:6,fill:'#f5edef'}),svgElement('rect',{x:1000-width,y:0,width,height:12,rx:6,fill:chartColors[i%chartColors.length]}));
  const title=svgElement('title');title.textContent=row.label+': '+digits(row.value);svg.append(title);line.append(label,svg);card.append(line);
 });card.append(dataTable(group.rows));return card;
}
const dayLabel=day=>new Intl.DateTimeFormat('fa-IR',{timeZone:'Asia/Tehran',month:'short',day:'numeric'}).format(new Date(day+'T12:00:00+03:30'));
function trendChart(trend){
 const card=element('article',undefined,'chart-card chart-trend');card.append(element('h3',trend.title));
 const points=trend.points,max=Math.max(1,...points.map(p=>p.value)),total=points.reduce((sum,p)=>sum+p.value,0);card.append(element('p','مجموع در بازه: '+digits(total),'chart-subtitle'));
 const svg=svgElement('svg',{viewBox:'0 0 740 210',role:'img','aria-label':trend.title});
 for(const value of new Set([0,Math.ceil(max/2),max])){const y=185-value/max*160;svg.append(svgElement('line',{x1:60,y1:y,x2:720,y2:y,stroke:'#ebdce0'}));const label=svgElement('text',{x:49,y:y+4,'text-anchor':'end'});label.textContent=digits(value);svg.append(label);}
 const coords=points.map((p,i)=>[60+i/Math.max(1,points.length-1)*660,185-p.value/max*160]);
 svg.append(svgElement('polyline',{points:coords.map(c=>c.join(',')).join(' '),fill:'none',stroke:'#943852','stroke-width':2.5,'stroke-linejoin':'round'}));
 points.forEach((p,i)=>{const dot=svgElement('circle',{cx:coords[i][0],cy:coords[i][1],r:3,fill:'#943852',tabindex:0,'aria-label':dayLabel(p.day)+': '+digits(p.value)}),title=svgElement('title');title.textContent=dayLabel(p.day)+': '+digits(p.value);dot.append(title);svg.append(dot);});card.append(svg);
 const range=element('div',undefined,'chart-range');range.append(element('span',dayLabel(points[0].day)),element('span',dayLabel(points.at(-1).day)));card.append(range,dataTable(points.map(p=>({label:dayLabel(p.day),value:p.value})),'روز'));return card;
}
function renderReports(report){$('report-note').textContent=report.notes;$('report-charts').replaceChildren(...report.groups.map(barChart),trendChart(report.timeline));}
function renderTable(){
 $('table-title').textContent=titles[state.table][0];$('table-subtitle').textContent=titles[state.table][1];
 $('new-message').hidden=state.table!=='catalog';$('previous').disabled=state.page===1;$('next').disabled=!state.hasMore;$('page-label').textContent='صفحهٔ '+digits(state.page);
 $('table-head').replaceChildren();$('table-body').replaceChildren();const header=document.createElement('tr');
 for(const key of columns[state.table]){const th=document.createElement('th');th.textContent=labels[key];header.append(th);}
 if(state.table==='catalog'){const th=document.createElement('th');th.textContent='ویرایش';header.append(th);}$('table-head').append(header);
 if(!state.rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=header.children.length;td.textContent='هنوز رکوردی ثبت نشده است.';tr.append(td);$('table-body').append(tr);return;}
 for(const row of state.rows){const tr=document.createElement('tr');
  for(const key of columns[state.table]){const td=document.createElement('td'),value=row[key];
   if(key==='status'){const badge=document.createElement('span');badge.className='status';badge.textContent=statuses[value]||value;td.append(badge);}
   else if(key.endsWith('_at'))td.textContent=date(value);
   else if(key==='active'||key==='enabled'||key==='done')td.textContent=value?'بله':'خیر';
   else td.textContent=value??'—';
   if(key==='body')td.className='message';if(key==='recipient')td.className='phone';if(key==='id'||key.endsWith('_id')||key==='device_hash')td.className='identifier';tr.append(td);
  }
  if(state.table==='catalog'){const td=document.createElement('td'),button=document.createElement('button');button.type='button';button.className='secondary';button.textContent='ویرایش';button.addEventListener('click',()=>edit(row));td.append(button);tr.append(td);}
  $('table-body').append(tr);
 }
}
function edit(row={}){$('message-id').value=row.id||'';$('message-body').value=row.body||'';$('message-category').value=row.category||'کوتاه';$('message-active').checked=row.active!==0;$('editor-title').textContent=row.id?'ویرایش پیام':'پیام جدید';$('editor-error').textContent='';$('editor').showModal();}
$('login-form').addEventListener('submit',async event=>{event.preventDefault();$('login-error').textContent='';const button=event.submitter;button.disabled=true;try{const result=await api('/api/admin/login',{method:'POST',body:JSON.stringify({password:$('password').value})});state.csrf=result.csrf;await openDashboard();}catch(e){$('login-error').textContent=e.message;}finally{button.disabled=false;}});
$('logout').addEventListener('click',async()=>{try{await api('/api/admin/logout',{method:'POST',body:'{}'});showLogin();}catch(e){$('notice').textContent=e.message;}});
for(const button of document.querySelectorAll('[data-table]'))button.addEventListener('click',()=>{state.table=button.dataset.table;state.page=1;for(const tab of document.querySelectorAll('[data-table]'))tab.classList.toggle('selected',tab===button);load();});
$('report-days').addEventListener('change',()=>{state.days=Number($('report-days').value);load();});
$('refresh').addEventListener('click',()=>{state.reportCache.clear();load();});$('previous').addEventListener('click',()=>{if(state.page>1){state.page--;load();}});$('next').addEventListener('click',()=>{if(state.hasMore){state.page++;load();}});
$('new-message').addEventListener('click',()=>edit());$('close-editor').addEventListener('click',()=>$('editor').close());
$('editor-form').addEventListener('submit',async event=>{event.preventDefault();event.submitter.disabled=true;try{await api('/api/admin/catalog',{method:'POST',body:JSON.stringify({id:$('message-id').value||undefined,body:$('message-body').value,category:$('message-category').value,active:$('message-active').checked})});$('editor').close();state.reportCache.clear();await load();}catch(e){$('editor-error').textContent=e.message;}finally{event.submitter.disabled=false;}});
(async()=>{try{const session=await api('/api/admin/session');state.csrf=session.csrf;await openDashboard();}catch{showLogin();}})();
