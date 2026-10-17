"""Comprueba que cada clase, método y campo del framework que usa el dex de un APK existe
en un android.jar dado (p. ej. el de API 10, Android 2.3.3, la versión del Galaxy Y).

Complementa a lint NewApi: revisa el bytecode final que generó D8 (incluido el desazucarado)
contra el SDK real, resolviendo miembros heredados.

Uso:
    python herramientas/verificar_api.py <android.jar> <dexdump> <apk>

- android.jar de API 10: https://dl.google.com/android/repository/android-2.3.3_r02.zip
  (API 9: android-2.3.1_r02.zip). Descomprimir solo android.jar.
- dexdump: está en <SDK>/build-tools/<versión>/ (dexdump.exe en Windows).

Termina con código 1 si falta algo. Las llamadas detrás de una guarda Build.VERSION.SDK_INT
aparecen como faltantes al comparar con un API menor: revisarlas a mano.
"""
import re
import struct
import subprocess
import sys
import zipfile

PREFIJOS_FRAMEWORK = ("android/", "java/", "javax/", "dalvik/", "org/")


def leer_clase(datos):
    """Devuelve (nombre, superclase, interfaces, miembros) de un .class."""
    n = struct.unpack(">H", datos[8:10])[0]
    cp = [None] * n
    i, k = 10, 1
    while k < n:
        tag = datos[i]
        if tag == 1:  # Utf8
            largo = struct.unpack(">H", datos[i + 1:i + 3])[0]
            cp[k] = datos[i + 3:i + 3 + largo].decode("utf-8", "replace")
            i += 3 + largo
        elif tag == 7:  # Class
            cp[k] = ("clase", struct.unpack(">H", datos[i + 1:i + 3])[0])
            i += 3
        elif tag in (5, 6):  # Long, Double: ocupan dos entradas
            i += 9
            k += 1
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        else:
            raise ValueError("tag de constant pool desconocido: %d" % tag)
        k += 1

    def nombre_clase(idx):
        return cp[cp[idx][1]] if idx else None

    _, este, sup = struct.unpack(">HHH", datos[i:i + 6])
    i += 6
    n_if = struct.unpack(">H", datos[i:i + 2])[0]
    i += 2
    interfaces = [nombre_clase(struct.unpack(">H", datos[i + 2 * j:i + 2 * j + 2])[0]) for j in range(n_if)]
    i += 2 * n_if
    miembros = set()
    for tipo in ("campo", "metodo"):
        cantidad = struct.unpack(">H", datos[i:i + 2])[0]
        i += 2
        for _ in range(cantidad):
            _, ni, di, n_attr = struct.unpack(">HHHH", datos[i:i + 8])
            i += 8
            miembros.add((tipo, cp[ni], cp[di]))
            for _ in range(n_attr):
                i += 6 + struct.unpack(">I", datos[i + 2:i + 6])[0]
    return nombre_clase(este), nombre_clase(sup), interfaces, miembros


def leer_jar(ruta):
    clases = {}
    with zipfile.ZipFile(ruta) as z:
        for nombre in z.namelist():
            if nombre.endswith(".class"):
                este, sup, interfaces, miembros = leer_clase(z.read(nombre))
                clases[este] = (sup, interfaces, miembros)
    return clases


def leer_dex(dexdump, apk):
    """Clases propias del dex (con superclase, interfaces y miembros) y referencias externas."""
    salida = subprocess.run([dexdump, "-d", apk], capture_output=True, text=True, check=True).stdout
    propias = {}
    actual = tipo = nombre = None
    en_interfaces = False
    for linea in salida.splitlines():
        m = re.match(r"\s+Class descriptor\s+:\s+'L([^;]+);'", linea)
        if m:
            actual = m.group(1)
            propias[actual] = [None, [], set()]
            en_interfaces = False
            continue
        if actual is None:
            continue
        m = re.match(r"\s+Superclass\s+:\s+'L([^;]+);'", linea)
        if m:
            propias[actual][0] = m.group(1)
            continue
        if re.match(r"\s+Interfaces\s+-", linea):
            en_interfaces = True
            continue
        if re.match(r"\s+(Static fields|Instance fields|Direct methods|Virtual methods)\s+-", linea):
            en_interfaces = False
            tipo = "campo" if "fields" in linea else "metodo"
            continue
        m = re.match(r"\s+#\d+\s+:\s+'L([^;]+);'", linea)
        if m and en_interfaces:
            propias[actual][1].append(m.group(1))
            continue
        m = re.match(r"\s+name\s+:\s+'([^']+)'", linea)
        if m:
            nombre = m.group(1)
            continue
        m = re.match(r"\s+type\s+:\s+'([^']+)'", linea)
        if m and nombre is not None and tipo is not None:
            propias[actual][2].add((tipo, nombre, m.group(1)))
            nombre = None
    referencias = set(re.findall(r"L([\w/$]+);\.([\w$<>]+):(\([^ ]*?\)[^ ,]+|[^ (,]+)", salida))
    tipos = {t for t in re.findall(r"L([\w/$]+);", salida) if t.startswith(PREFIJOS_FRAMEWORK)}
    return propias, referencias, tipos


def existe(clases, propias, clase, miembro, vistas):
    """Busca el miembro en la clase, sus superclases e interfaces (propias o del framework)."""
    if clase is None or clase in vistas:
        return False
    vistas.add(clase)
    info = propias.get(clase) or clases.get(clase)
    if info is None:
        return False
    sup, interfaces, miembros = info
    if miembro in miembros:
        return True
    return any(existe(clases, propias, c, miembro, vistas) for c in [sup] + list(interfaces))


def main(jar, dexdump, apk):
    clases = leer_jar(jar)
    propias, referencias, tipos = leer_dex(dexdump, apk)
    faltan = ["clase " + t for t in sorted(tipos) if t not in clases and t not in propias]
    revisadas = 0
    for clase, nombre, descriptor in sorted(referencias):
        tipo = "metodo" if descriptor.startswith("(") else "campo"
        miembro = (tipo, nombre, descriptor)
        if clase in propias and miembro in propias[clase][2]:
            continue
        revisadas += 1
        if not existe(clases, propias, clase, miembro, set()):
            faltan.append("%s %s.%s %s" % (tipo, clase, nombre, descriptor))
    print("referencias externas revisadas: %d · tipos del framework: %d" % (revisadas, len(tipos)))
    for f in faltan:
        print("NO EXISTE en %s: %s" % (jar, f))
    print("faltantes: %d" % len(faltan))
    return 1 if faltan else 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
