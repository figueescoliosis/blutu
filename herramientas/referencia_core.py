"""Implementación de referencia, independiente de Java, del códec IMA ADPCM, el decimador ×2 y
el downmix de core. Imprime los CRC32 que fijan los tests de determinismo de core.

Uso: python herramientas/referencia_core.py   (Python puro)

Si un cambio en core altera la salida, los tests fallan. Si el cambio es intencional, hay que
reproducirlo aquí y actualizar los CRC en los tests.
"""
import zlib

PASOS = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
         50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
         337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
         1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
         8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
         32767]
AJUSTE = [-1, -1, -1, -1, 2, 4, 6, 8] * 2
LADO = [-3, 13, -35, 77, -148, 261, -433, 693, -1096, 1787, -3290, 10367]
CENTRO = 16382


def saturar(v):
    return max(-32768, min(32767, v))


def senal(frames, canales, semilla):
    """Señal sintética solo con enteros: triángulo con barrido + ruido de un LCG."""
    s = []
    x = semilla
    fase, inc = 0, 50
    for i in range(frames):
        fase = (fase + inc) & 0xFFFF
        if i % 64 == 63:
            inc = inc % 3000 + 7
        tri = fase - 16384 if fase < 32768 else 49151 - fase
        for c in range(canales):
            x = (x * 1103515245 + 12345) & 0x7FFFFFFF
            ruido = (x >> 16) - 16384
            s.append(saturar(tri * (c + 1) + (ruido >> 2)))
    return s


def reconstruir(n, pred, ind):
    paso = PASOS[ind]
    dif = paso >> 3
    if n & 4:
        dif += paso
    if n & 2:
        dif += paso >> 1
    if n & 1:
        dif += paso >> 2
    pred = saturar(pred - dif if n & 8 else pred + dif)
    return pred, max(0, min(88, ind + AJUSTE[n]))


def codificar(pcm, canales, mpb=1024):
    frames = len(pcm) // canales
    estado = [(0, 0)] * canales
    salida = bytearray()
    for inicio in range(0, frames, mpb):
        cab, datos = bytearray(), bytearray()
        for c in range(canales):
            pred, ind = estado[c]
            cab += (pred & 0xFFFF).to_bytes(2, "big") + bytes([ind, 0])
            nibbles = []
            for i in range(mpb):
                f = inicio + i
                x = pcm[f * canales + c] if f < frames else 0
                paso = PASOS[ind]
                diff = x - pred
                n = 0
                if diff < 0:
                    n, diff = 8, -diff
                if diff >= paso:
                    n |= 4
                    diff -= paso
                if diff >= paso >> 1:
                    n |= 2
                    diff -= paso >> 1
                if diff >= paso >> 2:
                    n |= 1
                pred, ind = reconstruir(n, pred, ind)
                nibbles.append(n)
            datos += bytes(nibbles[k] | (nibbles[k + 1] << 4) for k in range(0, mpb, 2))
            estado[c] = (pred, ind)
        salida += cab + datos
    return bytes(salida)


def decodificar(flujo, canales, mpb=1024):
    largo = canales * (4 + mpb // 2)
    pcm = []
    for b in range(0, len(flujo), largo):
        bloque = flujo[b:b + largo]
        canal = []
        for c in range(canales):
            pred = int.from_bytes(bloque[4 * c:4 * c + 2], "big", signed=True)
            ind = min(bloque[4 * c + 2], 88)
            datos = bloque[4 * canales + c * mpb // 2:4 * canales + (c + 1) * mpb // 2]
            muestras = []
            for i in range(mpb):
                n = datos[i >> 1] & 0x0F if i % 2 == 0 else datos[i >> 1] >> 4
                pred, ind = reconstruir(n, pred, ind)
                muestras.append(pred)
            canal.append(muestras)
        for i in range(mpb):
            for c in range(canales):
                pcm.append(canal[c][i])
    return pcm


def decimar(pcm, canales):
    h = [0] * 47
    for j, v in enumerate(LADO):
        h[2 * j] = h[46 - 2 * j] = v
    h[23] = CENTRO
    frames = len(pcm) // canales
    salida = []
    for n in range(1, frames, 2):
        for c in range(canales):
            acc = sum(h[k] * pcm[(n - k) * canales + c] for k in range(47) if n - k >= 0)
            salida.append(saturar((acc + 16384) >> 15))
    return salida


def a_mono(pcm, canales):
    salida = []
    for f in range(len(pcm) // canales):
        suma = sum(pcm[f * canales:(f + 1) * canales])
        mitad = canales // 2
        y = (suma + mitad) // canales if suma >= 0 else -((-suma + mitad) // canales)
        salida.append(saturar(y))
    return salida


def crc_pcm(pcm):
    return zlib.crc32(b"".join((v & 0xFFFF).to_bytes(2, "big") for v in pcm))


def main():
    estereo = senal(10 * 1024 + 300, 2, 12345)
    flujo = codificar(estereo, 2)
    print(f"IMA estéreo, {len(flujo)} bytes: CRC32 0x{zlib.crc32(flujo):08X}")
    print(f"IMA decodificado: CRC32 0x{crc_pcm(decodificar(flujo, 2)):08X}")
    mono = senal(3 * 1024 + 1, 1, 777)
    print(f"IMA mono: CRC32 0x{zlib.crc32(codificar(mono, 1)):08X}")
    print(f"Decimador estéreo: CRC32 0x{crc_pcm(decimar(estereo, 2)):08X}")
    seis = senal(2000, 6, 99)
    print(f"Downmix 6 canales: CRC32 0x{crc_pcm(a_mono(seis, 6)):08X}")
    print(f"Downmix estéreo: CRC32 0x{crc_pcm(a_mono(estereo, 2)):08X}")


if __name__ == "__main__":
    main()
