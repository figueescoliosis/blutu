# Firma de depuración compartida

`debug.keystore` es la clave **de depuración** de ParlanteY. No es secreta: contraseña `android`, alias `androiddebugkey`, contraseña de la clave `android`.

Está en el repo para que los APK compilados en cualquier máquina tengan la misma firma: los de las releases de GitHub, los del CI y los que compiles en Windows. Así se puede instalar uno encima del otro con `adb install -r` sin desinstalar antes.

SHA-256 del certificado: `75:BF:D9:84:9F:A5:8A:6C:C4:18:AF:D4:0D:C4:5D:A0:0E:78:3F:8D:C6:FC:14:71:19:AF:38:74:F1:29:5F:CF`
