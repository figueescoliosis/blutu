"""Diseña el FIR de media banda de DecimadorX2 y escribe su tabla Q15 para Java.

Uso: python herramientas/disenar_mediabanda.py   (Python puro, sin numpy)

Ventana de Kaiser sobre la respuesta ideal de media banda (corte en fs/4). Los coeficientes se
redondean a Q15; el central se ajusta para que la suma sea exactamente 32768 (ganancia DC = 1).
Con N = 47 y beta = 7 da ≥ 68,9 dB de atenuación entre 0,3 y 0,5 fs (mínimo 68,97 dB cerca de
0,303 fs) y rizado ≤ 0,003 dB entre 0 y 0,2 fs (fs = tasa de entrada).
"""
import math

N = 47      # largo; 4k + 3 para que los extremos no sean cero
BETA = 7.0  # parámetro de la ventana de Kaiser


def i0(x):
    """Función de Bessel modificada de orden 0 (serie)."""
    suma, termino, k = 1.0, 1.0, 1
    while termino > 1e-12 * suma:
        termino *= (x / 2 / k) ** 2
        suma += termino
        k += 1
    return suma


def coeficientes_q15():
    m = (N - 1) // 2
    h = []
    for n in range(N):
        d = n - m
        ideal = 0.5 if d == 0 else math.sin(math.pi * d / 2) / (math.pi * d)
        ventana = i0(BETA * math.sqrt(1 - (2 * n / (N - 1) - 1) ** 2)) / i0(BETA)
        h.append(ideal * ventana)
    total = sum(h)
    q = [round(v / total * 32768) for v in h]
    for n in range(N):
        if n != m and (n - m) % 2 == 0:
            q[n] = 0  # en media banda, los coeficientes pares respecto del centro son cero
    q[m] += 32768 - sum(q)
    return q


def respuesta_db(q, f):
    re = sum(c * math.cos(2 * math.pi * f * n) for n, c in enumerate(q)) / 32768
    im = sum(c * math.sin(2 * math.pi * f * n) for n, c in enumerate(q)) / 32768
    return 20 * math.log10(max(1e-12, math.hypot(re, im)))


def main():
    q = coeficientes_q15()
    m = (N - 1) // 2
    lado = [q[n] for n in range(0, m, 2)]
    atenuacion = -max(respuesta_db(q, f / 2000) for f in range(600, 1001))
    rizado = max(abs(respuesta_db(q, f / 2000)) for f in range(0, 401))
    print(f"// N = {N}, Kaiser beta = {BETA}: atenuación {atenuacion:.2f} dB (0,3–0,5 fs), "
          f"rizado {rizado:.4f} dB (0–0,2 fs)")
    print(f"static final int[] LADO = {{{', '.join(map(str, lado))}}};")
    print(f"static final int CENTRO = {q[m]};")


if __name__ == "__main__":
    main()
