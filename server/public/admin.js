'use strict';
const $=id=>document.getElementById(id);
const state={csrf:'',table:'catalog',page:1,rows:[],hasMore:false};
const titles={catalog:['بانک پیام‌ها','متن‌های پیشنهادی را ببین و ویرایش کن.'],installations:['نصب‌ها','هر نصب پس از نخستین اتصال به سرور ثبت می‌شود.'],queue:['صف و وضعیت پیام‌ها','آخرین وضعیت دریافت‌شده از گوشی کاربران.'],events:['روند پیام‌ها','تغییر وضعیت‌ها، حتی پس از همگام‌سازی با تأخیر.'],sent:['گزارش ارسال','متن، مخاطب و شناسهٔ نصب ارسال‌کننده.']};
const labels={id:'شناسه',body:'متن پیام',category:'دسته‌بندی',active:'فعال',created_at:'زمان ثبت',device_hash:'شناسهٔ دستگاه',app_version:'نسخهٔ اپ',enabled:'فعال',last_seen_at:'آخرین اتصال',installation_id:'شناسهٔ نصب',recipient:'شمارهٔ مخاطب',status:'وضعیت',sim_id:'شناسهٔ سیم‌کارت',revision:'نسخهٔ رکورد',approved_at:'افزودن به صف',planned_at:'زمان برنامه‌ریزی‌شده',submitted_at:'زمان درخواست ارسال',updated_at:'آخرین همگام‌سازی',message_id:'شناسهٔ پیام',occurred_at:'زمان رخداد',sent_at:'زمان ارسال',received_at:'زمان دریافت گزارش'};
const statuses={APPROVED:'در صف',SCHEDULED:'زمان‌بندی‌شده',IMMEDIATE:'آمادهٔ ارسال فوری',SENDING:'در حال ارسال',SENT:'ارسال به شبکه تأیید شد',FAILED:'ناموفق',UNCERTAIN:'نتیجه نامشخص',CANCELLED:'لغوشده'};
const columns={catalog:['body','category','active','created_at'],installations:['id','device_hash','app_version','enabled','created_at','last_seen_at'],queue:['body','recipient','status','installation_id','sim_id','approved_at','planned_at','submitted_at','updated_at'],events:['message_id','installation_id','status','revision','occurred_at'],sent:['body','recipient','installation_id','message_id','status','sent_at','received_at']};
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
 $('notice').textContent='';document.body.setAttribute('aria-busy','true');$('connection').textContent='در حال دریافت…';
 try{
  const [stats,data]=await Promise.all([api('/api/admin/stats'),api('/api/admin/tables/'+state.table+'?page='+state.page)]);
  for(const key of ['installations','devices','catalog','queue','sent'])$('count-'+key).textContent=digits(stats[key]);
  state.rows=data.rows;state.hasMore=data.has_more;renderTable();$('connection').textContent='متصل';
 }catch(e){$('notice').textContent=e.message;$('connection').textContent='نیاز به بررسی';}
 finally{document.body.removeAttribute('aria-busy');}
}
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
   else if(key==='active'||key==='enabled')td.textContent=value?'بله':'خیر';
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
$('refresh').addEventListener('click',load);$('previous').addEventListener('click',()=>{if(state.page>1){state.page--;load();}});$('next').addEventListener('click',()=>{if(state.hasMore){state.page++;load();}});
$('new-message').addEventListener('click',()=>edit());$('close-editor').addEventListener('click',()=>$('editor').close());
$('editor-form').addEventListener('submit',async event=>{event.preventDefault();event.submitter.disabled=true;try{await api('/api/admin/catalog',{method:'POST',body:JSON.stringify({id:$('message-id').value||undefined,body:$('message-body').value,category:$('message-category').value,active:$('message-active').checked})});$('editor').close();await load();}catch(e){$('editor-error').textContent=e.message;}finally{event.submitter.disabled=false;}});
(async()=>{try{const session=await api('/api/admin/session');state.csrf=session.csrf;await openDashboard();}catch{showLogin();}})();
