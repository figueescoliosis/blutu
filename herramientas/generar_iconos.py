"""Genera los íconos PNG de las dos apps (sin vectores ni adaptive icons).

Uso: python herramientas/generar_iconos.py   (requiere Pillow)
"""
from pathlib import Path

from PIL import Image, ImageDraw

RAIZ = Path(__file__).resolve().parent.parent

# Receptor: drawable-*, que entiende Android 2.3 (el flavor mic, en verde). Emisor: mipmap-*.
# ic_notificacion: ícono de la barra de estado, blanco sobre transparente (24 dp; en 2.3 ocupa
# 24×24 px en mdpi). Va en drawable-* en las dos apps.
DESTINOS = {
    "receptor/src/main": ("drawable", (0xE6, 0x7E, 0x22),
                          {"ldpi": 36, "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144}),
    "receptor/src/mic": ("drawable", (0x27, 0xAE, 0x60),
                         {"ldpi": 36, "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144}),
    "emisor/src/main": ("mipmap", (0x29, 0x80, 0xB9),
                        {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}),
}


def icono(lado, color):
    """Parlante con ondas sobre un círculo de color, dibujado a 8x y reducido (antialias)."""
    s = lado * 8
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = s * 0.04
    d.ellipse([m, m, s - m, s - m], fill=color + (255,))
    blanco = (255, 255, 255, 255)
    # Cuerpo y cono del parlante.
    d.rectangle([s * 0.22, s * 0.40, s * 0.34, s * 0.60], fill=blanco)
    d.polygon([(s * 0.34, s * 0.40), (s * 0.50, s * 0.26), (s * 0.50, s * 0.74), (s * 0.34, s * 0.60)],
              fill=blanco)
    # Ondas.
    ancho = max(1, int(s * 0.05))
    for r in (0.12, 0.22):
        caja = [s * 0.50 - s * r + s * 0.06, s * 0.50 - s * r, s * 0.50 + s * r + s * 0.06, s * 0.50 + s * r]
        d.arc(caja, start=-50, end=50, fill=blanco, width=ancho)
    return img.resize((lado, lado), Image.LANCZOS)


def notificacion(lado):
    """Parlante con una onda, blanco sobre transparente, dibujado a 8x y reducido."""
    s = lado * 8
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    blanco = (255, 255, 255, 255)
    d.rectangle([s * 0.10, s * 0.36, s * 0.26, s * 0.64], fill=blanco)
    d.polygon([(s * 0.26, s * 0.36), (s * 0.50, s * 0.14), (s * 0.50, s * 0.86), (s * 0.26, s * 0.64)],
              fill=blanco)
    ancho = max(1, int(s * 0.09))
    for r in (0.16, 0.32):
        caja = [s * 0.50 - r * s, s * 0.50 - r * s, s * 0.50 + r * s, s * 0.50 + r * s]
        d.arc(caja, start=-55, end=55, fill=blanco, width=ancho)
    return img.resize((lado, lado), Image.LANCZOS)


NOTIFICACION = {
    "receptor": {"ldpi": 18, "mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72},
    "emisor": {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96},
}


def main():
    for modulo, densidades in NOTIFICACION.items():
        for densidad, lado in densidades.items():
            carpeta = RAIZ / modulo / "src" / "main" / "res" / f"drawable-{densidad}"
            carpeta.mkdir(parents=True, exist_ok=True)
            notificacion(lado).save(carpeta / "ic_notificacion.png", optimize=True)
            print(carpeta / "ic_notificacion.png", lado)
    for fuentes, (tipo, color, densidades) in DESTINOS.items():
        for densidad, lado in densidades.items():
            carpeta = RAIZ / fuentes / "res" / f"{tipo}-{densidad}"
            carpeta.mkdir(parents=True, exist_ok=True)
            icono(lado, color).save(carpeta / "ic_launcher.png", optimize=True)
            print(carpeta / "ic_launcher.png", lado)


if __name__ == "__main__":
    main()
