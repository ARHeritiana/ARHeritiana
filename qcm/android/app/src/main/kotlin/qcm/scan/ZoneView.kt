package qcm.scan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import qcm.core.Zone

/**
 * Affiche la grille redressée ; les cases lues comme cochées sont surlignées en vert,
 * les cases douteuses sont entourées en orange. Toucher une case inverse son état.
 */
@SuppressLint("ViewConstructor")
class ZoneView(ctx: Context, private val image: Bitmap, val cochees: Array<BooleanArray>,
               private val douteuses: Array<BooleanArray>, private val surChangement: () -> Unit) : View(ctx) {
    // partie affichée de la zone, en mm (la grille, sans le QR)
    private val xMin = 7.0; private val xMax = 131.0
    private val largeur get() = xMax - xMin
    private val d = resources.displayMetrics.density
    private val plein = Paint().apply { color = Color.argb(70, 30, 170, 60); style = Paint.Style.FILL }
    private val vert = Paint().apply { color = Color.rgb(20, 140, 50); style = Paint.Style.STROKE; strokeWidth = 2.5f * d; isAntiAlias = true }
    private val orange = Paint().apply { color = Color.rgb(240, 120, 0); style = Paint.Style.STROKE; strokeWidth = 4f * d; isAntiAlias = true }
    private val filtre = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, Math.round(w * Zone.H / largeur).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val k = width / largeur                       // pixels écran par mm
        val p = Photo.PX_PAR_MM
        canvas.drawBitmap(image, Rect((xMin * p).toInt(), 0, (xMax * p).toInt(), image.height),
            Rect(0, 0, width, height), filtre)
        val demi = (Zone.CASE / 2 + 0.6) * k
        for (i in 0 until Zone.LIGNES) for (j in 0 until Zone.COLONNES) {
            val cx = (Zone.caseX(j) - xMin) * k; val cy = Zone.caseY(i) * k
            val r = RectF((cx - demi).toFloat(), (cy - demi).toFloat(), (cx + demi).toFloat(), (cy + demi).toFloat())
            if (cochees[i][j]) { canvas.drawRect(r, plein); canvas.drawRect(r, vert) }
            if (douteuses[i][j]) canvas.drawRect(r, orange)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val k = width / largeur
            val j = Math.round((e.x / k + xMin - Zone.X0) / Zone.DX).toInt()
            val i = Math.round((e.y / k - Zone.Y0) / Zone.DY).toInt()
            if (i in 0 until Zone.LIGNES && j in 0 until Zone.COLONNES) {
                cochees[i][j] = !cochees[i][j]
                douteuses[i][j] = false
                invalidate()
                surChangement()
            }
        }
        return true
    }
}
