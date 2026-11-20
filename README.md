# Taller

App Android (pensada para el Motorola Edge 50 Ultra, Android 11+) con herramientas que usan la cámara y los sensores:

- **Color:** cuentagotas. Apuntás a una pared, una tela o un objeto y muestra el color del centro (promedio de una zona chica), su código HEX, RGB y HSL, el nombre más parecido y colores que combinan (complementario, análogos, tríada, complementario dividido y tonos). Congelar, paleta guardada, linterna y **calibrar blanco** con un papel (bloquea balance de blancos y exposición).
- **Nivel:** sensor de gravedad. Acostado: burbuja en dos ejes. De canto (apoyado en un cuadro): grados que faltan y la línea del horizonte real. Vibra al quedar derecho; "Poner en cero" por posición.
- **Medir con 2 lentes:** el principal y el tele a la vez (cámara lógica multi-lente, como DualCam). Se congela un par de cuadros sincronizados, se marcan dos puntos sobre la imagen del tele y se calcula la distancia a cada uno y el largo entre ellos (visión estéreo). Calibración: "infinito" (una vez, apuntando a algo lejano) y opcional con una medida conocida. Mejor de 0,3 a 3 m.
- **Regla:** en la pantalla, en cm y mm, con ajuste usando una tarjeta (8,56 cm).

Cada push compila el APK con GitHub Actions y lo publica como release `build-N` (firma fija: se instala encima).
