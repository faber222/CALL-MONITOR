# CallMonitor

Aplicativo Java Swing de monitoria de chamadas estéreo. Separa os dois canais de
uma gravação (cliente = esquerdo, analista = direito) via ffmpeg, exibe os
waveforms e permite reproduzir, ajustar velocidade/ganho e recortar trechos em
MP3.

- Código: arquivo único `CallMonitor.java`.
- Requisitos: JDK 17+ e ffmpeg (embutido na instalação, ou no PATH em dev).
- Build: `build.bat` (Windows); instalador via `CallMonitor.iss`.

## Versionamento

O projeto segue SemVer no formato **`vX.Y.Z`**:

- **X** (major) — mudança total de layout
- **Y** (minor) — implementação de função nova
- **Z** (patch) — correção de bug

**Versão atual: 1.0.1**

A cada nova mudança, aplicar o incremento correspondente (Z para correção de bug,
Y para função nova, X para mudança de layout) e **atualizar o número de versão
neste arquivo** — ele é a fonte única da versão atual.

Histórico:
- **1.0.1** — otimização de memória: PCM lido do disco com janela deslizante
  (evita estouro de memória em chamadas longas).
