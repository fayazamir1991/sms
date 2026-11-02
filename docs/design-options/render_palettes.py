from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json,math
root=Path(__file__).resolve().parents[2]
out=Path(__file__).resolve().parent;out.mkdir(parents=True,exist_ok=True)
palettes=[
 {'id':1,'title':'لاکی و طلایی','subtitle':'پرشوق، درخشان و متعادل — پیشنهاد من','gold_ink':'#8D6D13','primary':'#C81D25','highlight':'#FF5A36','gold':'#D4AF37','canvas':'#FFFAF2','surface':'#FFFFFF','ink':'#241915','muted':'#796B62','soft':'#FFF0DC'},
 {'id':2,'title':'ورمیلیون و شامپاینی','subtitle':'جسور، شاداب و گرم','gold_ink':'#8C5C1A','primary':'#C52A0C','highlight':'#FF7A18','gold':'#EDC37D','canvas':'#FFF8ED','surface':'#FFFFFF','ink':'#2B1D16','muted':'#806F5F','soft':'#FFF0D6'},
 {'id':3,'title':'زرشکی و طلایی','subtitle':'عمیق، باوقار و لوکس؛ آرام‌تر','gold_ink':'#876019','primary':'#940F1D','highlight':'#DA322F','gold':'#C9A24D','canvas':'#FBF5E9','surface':'#FFFFFF','ink':'#241918','muted':'#7B6A63','soft':'#F6EBD5'},
 {'id':4,'title':'قرمز و گرافیت','subtitle':'تیره، پرکنتراست و چشمگیر','gold_ink':'#E9B44C','primary':'#D2221B','highlight':'#FF612B','gold':'#E9B44C','canvas':'#151719','surface':'#24272B','ink':'#F8F4EC','muted':'#BEB6AA','soft':'#34312B'},
]
(out/'palettes.json').write_text(json.dumps(palettes,ensure_ascii=False,indent=2)+'\n')
fontroot=root/'app/src/main/res/font'
def font(size,bold=False):return ImageFont.truetype(str(fontroot/('vazirmatn_medium.ttf' if bold else 'vazirmatn_regular.ttf')),size)
im=Image.new('RGB',(1680,1830),'#F1EFE9');d=ImageDraw.Draw(im)
def rtl(text,xy,size,color,anchor='ra',bold=False):d.text(xy,text,font=font(size,bold),fill=color,anchor=anchor,direction='rtl',language='fa')
def ltr(text,xy,size,color,anchor='la'):d.text(xy,text,font=font(size),fill=color,anchor=anchor)
rtl('انتخاب پالت دل‌نوشته',(1620,37),44,'#211E19',bold=True)
rtl('بدون صورتی  •  پیش‌نمایش رنگ‌ها؛ نه تصویر نسخهٔ نهایی اپ',(1620,105),25,'#6B655C')

def rgb(c):return tuple(int(c[i:i+2],16) for i in (1,3,5))
def blend(a,b,t):return tuple(round(x*(1-t)+y*t) for x,y in zip(rgb(a),rgb(b)))
for j,p in enumerate(palettes):
 col=1-j%2;row=j//2;x=40+col*820;y=175+row*810;w=780;h=775
 d.rounded_rectangle((x,y,x+w,y+h),radius=30,fill=p['canvas'],outline='#DCD6CA',width=2)
 rtl(str(p['id'])+'  '+p['title'],(x+w-30,y+23),34,p['ink'],bold=True)
 rtl(p['subtitle'],(x+w-30,y+82),23,p['muted'])
 # Same small UI composition in every panel to compare only the palette.
 sx=x+28;sy=y+143;sw=w-56
 d.rounded_rectangle((sx,sy,sx+sw,sy+385),radius=24,fill=p['surface'])
 rtl('دل‌نوشته',(sx+sw-25,sy+15),31,p['ink'],bold=True)
 rtl('چند کلمه، از دل تو',(sx+sw-25,sy+64),19,p['muted'])
 d.line((sx+25,sy+105,sx+sw-25,sy+105),fill=p['gold'],width=2)
 rtl('دوستت دارم؛ همین قدر ساده،',(sx+sw-25,sy+125),27,p['ink'])
 rtl('همین قدر زیاد.',(sx+sw-25,sy+175),27,p['ink'])
 bx=sx+24;by=sy+249;bw=sw-48;bh=64
 button=Image.new('RGB',(bw,bh));pixels=button.load()
 for yy in range(bh):
  for xx in range(bw):
   # Saturated warm-red lacquer glint; center behind white label stays the primary red.
   t=0.18*math.exp(-((yy-2)/7)**2)*(.45+.55*xx/bw)
   pixels[xx,yy]=blend(p['primary'],p['highlight'],t)
 mask=Image.new('L',(bw,bh));ImageDraw.Draw(mask).rounded_rectangle((0,0,bw-1,bh-1),radius=17,fill=255)
 im.paste(button,(bx,by),mask)
 rtl('افزودن به صف',(bx+bw/2,by+10),26,'#FFFFFF',anchor='ma',bold=True)
 rtl('زمان دلخواه',(sx+sw-25,sy+331),21,p['gold_ink'],bold=True)
 rtl('درخشش ظریف، سطح تمیز و سایهٔ نرم',(x+w-30,y+550),21,p['muted'])
 labels=[('قرمز',p['primary']),('درخشش',p['highlight']),('طلایی',p['gold']),('زمینه',p['canvas'])]
 for k,(label,c) in enumerate(labels):
  xx=x+w-30-(k+1)*174+12
  d.rounded_rectangle((xx,y+610,xx+158,y+667),radius=12,fill=c,outline='#BEB7AD',width=1)
  rtl(label,(xx+158,y+677),19,p['muted'])
  ltr(c,(xx,y+711),18,p['ink'])
rtl('۱ و ۲ شوق بیشتری دارند؛ ۳ رسمی‌تر است؛ ۴ نسخهٔ تیره است.',(1620,1800),22,'#625B51',anchor='rd')
im.save(out/'palettes.png')
print(out/'palettes.png')
