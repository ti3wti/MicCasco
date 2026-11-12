# Mic Casco

App Android (8.0 o superior) que obliga al teléfono a usar el micrófono Bluetooth del casco para toda la grabación: notas de voz de WhatsApp, Asistente de Google, grabadoras, etc.

## Compilar el APK (sin instalar nada)
1. Cree un repositorio en GitHub (puede ser privado) y suba el contenido de esta carpeta, incluida la carpeta oculta `.github`.
2. Abra la pestaña **Actions**. La compilación "Compilar APK" arranca sola y tarda unos 3 minutos.
3. Al terminar, abra la ejecución y descargue el artefacto **MicCasco-apk** (es un zip con `app-debug.apk`).
4. Pase el APK al teléfono e instálelo. Tendrá que permitir "instalar apps desconocidas".

También puede abrir la carpeta en Android Studio y presionar Run.

## Uso
1. Abra la app y conceda los permisos de Bluetooth y notificaciones.
2. Elija su headset (o la pantalla AOOCCI si el casco está emparejado con ella).
3. Presione **ACTIVAR MICRÓFONO DEL CASCO**, o use el botón "Mic Casco" de los Ajustes rápidos.
4. Pruebe con **Grabar 5 s y escuchar**: debe aparecer "CASCO / Bluetooth ✔".
5. Recomendado: quite la optimización de batería y active el inicio automático al conectar el headset.

## Limitaciones
- Mientras está activa, la música va por el canal de voz (mono, menor calidad).
- La palabra "Ok Google" la detecta el teléfono. Abra el Asistente con el botón del headset: después de eso la orden se escucha por el casco.
- Algunos fabricantes (Xiaomi, Samsung, etc.) cierran apps en segundo plano. Quite la optimización de batería.
