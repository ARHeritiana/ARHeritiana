"""Simule des photos de copies : coches dessinées, perspective, ombre, flou, bruit, JPEG."""
import sys, random, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
K = 300 / 25.4
ZX, ZY = 16.0, 244.0            # origine de la zone sur la page (mm)

def centre(i, j): return ((ZX + 24 + 11 * j) * K, (ZY + 12 + 7.5 * i) * K)

def coche(d, i, j, style, rnd):
    x, y = centre(i, j); r = 2.3 * K
    jit = lambda: rnd.uniform(-0.5, 0.5) * K
    if style == 'croix':
        d.line([x - r + jit(), y - r + jit(), x + r + jit(), y + r + jit()], fill=40, width=5)
        d.line([x - r + jit(), y + r + jit(), x + r + jit(), y - r + jit()], fill=40, width=5)
    elif style == 'crayon':
        d.line([x - r, y - r + jit(), x + r, y + r + jit()], fill=120, width=6)
        d.line([x - r, y + r + jit(), x + r, y - r + jit()], fill=120, width=6)
    elif style == 'v':
        d.line([x - r, y, x - r / 3, y + r * 0.8, x + r * 1.3, y - r * 1.4], fill=50, width=6)
    elif style == 'plein':
        d.rectangle([x - r, y - r, x + r, y + r], fill=35)
    elif style == 'gribouille':
        for _ in range(9):
            d.line([x - r + jit(), y + rnd.uniform(-r, r), x + r + jit(), y + rnd.uniform(-r, r)], fill=60, width=4)
    elif style == 'point':                      # petite trace parasite (ne doit PAS compter)
        d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=90)

def coeffs(dst, src):
    A = []
    for (x, y), (u, v) in zip(dst, src):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); A.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    return np.linalg.solve(np.array(A, float), np.array(src, float).reshape(8))

def photo(page, sortie, marques, seed, taille=(3000, 4000), rot=0, tilt=0.06, ombre=True, flou=1.2, bruit=6, q=60):
    rnd = random.Random(seed); np.random.seed(seed)
    im = Image.open(page).convert('L')
    d = ImageDraw.Draw(im)
    for (i, j, style) in marques: coche(d, i, j, style, rnd)
    W, H = taille if rot % 180 == 0 else taille[::-1]      # page couchée : photo en paysage
    w, h = im.size
    m = 0.06 * W
    quad = [(m, m), (W - m, m), (W - m, H - m), (m, H - m)]
    quad = [(x + rnd.uniform(-tilt, tilt) * W, y + rnd.uniform(-tilt, tilt) * H) for x, y in quad]
    for _ in range(rot // 90): quad = quad[1:] + quad[:1]
    # quad[k] = position dans la photo du coin k de la page (HG, HD, BD, BG)
    c = coeffs(quad, [(0, 0), (w, 0), (w, h), (0, h)])
    fond = Image.new('L', (W, H), 70)
    masque = Image.new('L', (w, h), 255).transform((W, H), Image.PERSPECTIVE, c, Image.BICUBIC)
    warp = im.transform((W, H), Image.PERSPECTIVE, c, Image.BICUBIC)
    fond.paste(warp, (0, 0), masque)
    a = np.asarray(fond, float)
    a = a * 0.86                                       # papier pas parfaitement blanc
    if ombre:
        yy, xx = np.mgrid[0:H, 0:W]
        a *= 0.55 + 0.45 * (0.5 * xx / W + 0.5 * (1 - yy / H))            # dégradé d'éclairage
        a *= 1 - 0.35 / (1 + np.exp(-(xx - 0.55 * W + 0.2 * yy) / 40.0))  # bord d'ombre franc
    out = Image.fromarray(np.clip(a, 0, 255).astype('uint8'))
    if flou: out = out.filter(ImageFilter.GaussianBlur(flou))
    a = np.asarray(out, float) + np.random.normal(0, bruit, (H, W))
    Image.fromarray(np.clip(a, 0, 255).astype('uint8')).save(sortie, quality=q)

if __name__ == '__main__':
    out = sys.argv[1]
    # copie 1 (Alain Térieur, clé ADABACCDDC) : 9 bonnes réponses + Q10 fausse, styles variés
    photo('e-1.png', out + '/photo1.jpg',
          [(0,0,'croix'),(3,1,'crayon'),(0,2,'v'),(1,3,'plein'),(0,4,'gribouille'),(2,5,'croix'),
           (2,6,'crayon'),(3,7,'v'),(3,8,'croix'),(0,9,'croix'),(1,0,'point')], seed=1)
    # copie 2 (Sacha Touille, clé CBCCCACDCA) : tournée de 90°, Q3 double, Q5 vide, petite résolution
    photo('e-2.png', out + '/photo2.jpg',
          [(2,0,'croix'),(1,1,'croix'),(2,2,'croix'),(0,2,'croix'),(2,3,'v'),(0,5,'crayon'),
           (2,6,'croix'),(0,7,'croix'),(2,8,'croix'),(0,9,'v')], seed=2, taille=(1600, 2133), rot=90, flou=0.8)
    # copie 3 (Teddy Fissile) : aucune case cochée, tête en bas, forte perspective
    photo('e-3.png', out + '/photo3.jpg', [], seed=3, rot=180, tilt=0.09, taille=(2448, 3264))
    # corrigé de la copie 1 (cases vertes pleines) : doit donner 10/10
    photo('pr-1.png', out + '/photo4.jpg', [], seed=4, taille=(2448, 3264), rot=270, ombre=False)
