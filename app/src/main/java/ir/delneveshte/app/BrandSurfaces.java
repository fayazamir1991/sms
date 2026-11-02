package ir.delneveshte.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Static lacquer lighting: a warm edge glint above the high-contrast label area. */
public final class BrandSurfaces {
    private BrandSurfaces() { }
    public static Drawable lacquer(float radius,float stroke){return new Lacquer(radius,stroke);}
    private static final class Lacquer extends Drawable {
        private final float radius,stroke;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        private final Path clip=new Path();
        private Shader base,glint;
        private int alpha=255;
        Lacquer(float r,float s){radius=r;stroke=s;}
        @Override protected void onBoundsChange(Rect bounds){
            rect.set(bounds);rect.inset(stroke/2,stroke/2);clip.reset();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);
            base=new LinearGradient(0,rect.top,0,Math.max(rect.top+1,rect.bottom),new int[]{0xffce2526,AppThemeTokens.ACCENT,AppThemeTokens.ACCENT_DEEP},new float[]{0f,.45f,1f},Shader.TileMode.CLAMP);
            glint=new LinearGradient(0,rect.top,0,rect.top+stroke*10,new int[]{0x50ff5a36,0x00ff5a36},null,Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas){
            paint.setAlpha(alpha);paint.setStyle(Paint.Style.FILL);paint.setShader(base);canvas.drawRoundRect(rect,radius,radius,paint);
            int saved=canvas.save();canvas.clipPath(clip);paint.setShader(glint);canvas.drawRect(rect.left,rect.top,rect.right,Math.min(rect.bottom,rect.top+stroke*10),paint);canvas.restoreToCount(saved);
            paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(0x66d4af37);paint.setAlpha(alpha*102/255);canvas.drawRoundRect(rect,radius,radius,paint);
        }
        @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);}
    }
}
