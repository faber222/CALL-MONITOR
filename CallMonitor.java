import javax.swing.*;
import javax.swing.border.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.concurrent.atomic.*;

/**
 * CallMonitor v2 — Monitoria de chamadas estéreo
 *
 * MODO JUNTO   : reproduz ambos os canais simultaneamente
 *                waveform L (cliente, azul) + waveform R (analista, verde)
 *                mute independente por canal
 *
 * MODO SEPARADO: reproduz apenas um canal por vez em stereo completo
 *                (L e R do fone recebem o mesmo canal)
 *                troca entre cliente / analista com botão Solo
 *
 * Requisitos: JDK 17+ e ffmpeg (embutido na instalação, ou no PATH em dev)
 */
public class CallMonitor extends JFrame {

    // ── Cores ───────────────────────────────────────────────────────────────
    private static final Color BG_DARK       = new Color(18, 18, 24);
    private static final Color BG_PANEL      = new Color(28, 28, 38);
    private static final Color BG_CONTROL    = new Color(38, 38, 52);
    private static final Color COLOR_CLIENT  = new Color(64, 156, 255);
    private static final Color COLOR_ANALYST = new Color(72, 220, 140);
    private static final Color COLOR_PLAY    = new Color(255, 200, 60);
    private static final Color TEXT_PRIMARY  = new Color(230, 230, 240);
    private static final Color TEXT_DIM      = new Color(120, 120, 145);
    private static final Color CURSOR_CLR    = new Color(255, 70, 70, 210);

    // ── Dados de áudio ──────────────────────────────────────────────────────
    // PCM 16-bit signed little-endian, mono, 44100 Hz.
    // O PCM NÃO é mantido na RAM: fica nos arquivos temporários em disco
    // (tmpLeft/tmpRight) e é lido sob demanda por PcmSource, que mantém uma
    // pequena janela deslizante em memória. Isso permite carregar chamadas de
    // várias horas sem estourar o heap.
    private PcmSource   leftSrc;
    private PcmSource   rightSrc;
    private AudioFormat monoFmt;
    private int         sampleRate   = 44100;
    private int         totalSamples = 0;

    // ── Playback ────────────────────────────────────────────────────────────
    private SourceDataLine  line;
    private Thread          playThread;
    private final AtomicBoolean playing     = new AtomicBoolean(false);
    private final AtomicBoolean muteLeft    = new AtomicBoolean(false);
    private final AtomicBoolean muteRight   = new AtomicBoolean(false);
    private final AtomicInteger position    = new AtomicInteger(0);
    private volatile float      speed       = 1.0f;
    // Ganho linear aplicado SÓ na reprodução (não afeta o recorte). 1.0 = 0 dB.
    private volatile float      gainLinear  = 1.0f;

    // ── Recorte (crop) ────────────────────────────────────────────────────────
    // Marcadores em amostras no PCM original. -1 = não definido.
    // O recorte salva em estéreo (cliente na esquerda, analista na direita),
    // cru: sem ganho e sem mudança de velocidade.
    private int     cropIn   = -1;
    private int     cropOut  = -1;
    private boolean cropMode = false;   // true = clique no waveform seleciona em vez de buscar

    // ── Modo ────────────────────────────────────────────────────────────────
    // false = JUNTO (ambos os canais), true = SEPARADO (só um canal por vez)
    private boolean modoSeparado = false;
    // Em modo separado: false = ouvindo cliente, true = ouvindo analista
    private boolean soloAnalista = false;

    // ── UI ──────────────────────────────────────────────────────────────────
    private WaveformPanel  waveLeft, waveRight;
    private JButton        btnLoad, btnPlay, btnStop;
    private JToggleButton  btnMuteLeft, btnMuteRight;
    private JToggleButton  btnModo;
    private JButton        btnSolo;
    private JLabel         lblFile, lblTime, lblStatus, lblSpeed, lblSolo, lblGain, lblCrop;
    private JSlider        speedSlider, gainSlider;
    private JToggleButton  btnCropMode;
    private JButton        btnCropSave, btnCropClear;
    private JProgressBar   loadBar;
    private JPanel         panelMuteJunto, panelSolo;

    // ── Temp files ──────────────────────────────────────────────────────────
    private File tmpLeft, tmpRight;

    // ═══════════════════════════════════════════════════════════════════════
    public CallMonitor() {
        super("CallMonitor — Monitoria de Chamadas");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(1020, 640));
        setMinimumSize(new Dimension(820, 520));
        getContentPane().setBackground(BG_DARK);
        aplicarIconeJanela();
        buildUI();
        pack();
        setLocationRelativeTo(null);

        // javax.swing.Timer qualificado — sem ambiguidade com java.util.Timer
        new javax.swing.Timer(33, e -> refreshProgress()).start();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cleanup(); }
        });
    }

    // Substitui o ícone padrão do Java (o boneco Duke) na barra de título,
    // no Alt+Tab e no dock. Usa o PNG "callmonitor-icon.png": primeiro como
    // recurso embutido no .jar, depois como arquivo ao lado do .jar ou na
    // pasta atual. Java não lê .ico nativamente, por isso é PNG.
    private void aplicarIconeJanela() {
        Image base = carregarIcone("callmonitor-icon.png");
        if (base == null) return;
        java.util.List<Image> tamanhos = new java.util.ArrayList<>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256})
            tamanhos.add(base.getScaledInstance(s, s, Image.SCALE_SMOOTH));
        setIconImages(tamanhos);
        try {
            if (Taskbar.isTaskbarSupported())
                Taskbar.getTaskbar().setIconImage(base);   // dock no macOS/Linux
        } catch (Exception ignored) { }
    }

    private Image carregarIcone(String nome) {
        // 1) recurso embutido no classpath (dentro do jar)
        try {
            java.net.URL url = CallMonitor.class.getResource("/" + nome);
            if (url != null) return javax.imageio.ImageIO.read(url);
        } catch (Exception ignored) { }
        // 2) arquivo ao lado do jar ou na pasta atual
        try {
            java.net.URI uri = CallMonitor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            File loc = new File(uri);
            File dir = loc.isFile() ? loc.getParentFile() : loc;
            File f = new File(dir, nome);
            if (f.isFile()) return javax.imageio.ImageIO.read(f);
            File cwd = new File(nome);
            if (cwd.isFile()) return javax.imageio.ImageIO.read(cwd);
        } catch (Exception ignored) { }
        return null;
    }

    private void refreshProgress() {
        if (totalSamples == 0) return;
        float pct = (float) position.get() / totalSamples;
        waveLeft.setProgress(pct);
        waveRight.setProgress(pct);
        lblTime.setText(fmtTime(position.get()) + " / " + fmtTime(totalSamples));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════════════════
    private void buildUI() {
        setLayout(new BorderLayout(0, 0));

        // ── Topo ────────────────────────────────────────────────────────────
        JPanel topBar = mkPanel(BG_CONTROL);
        topBar.setLayout(new BorderLayout(12, 0));
        topBar.setBorder(new EmptyBorder(10, 16, 10, 16));

        lblFile = new JLabel("Nenhum arquivo carregado");
        lblFile.setForeground(TEXT_DIM);
        lblFile.setFont(new Font("SansSerif", Font.PLAIN, 12));

        btnLoad = mkButton("Abrir áudio", COLOR_PLAY, BG_CONTROL);
        btnLoad.addActionListener(e -> openFile());

        topBar.add(lblFile, BorderLayout.CENTER);
        topBar.add(btnLoad, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ── Waveforms ────────────────────────────────────────────────────────
        JPanel center = mkPanel(BG_DARK);
        center.setLayout(new GridLayout(2, 1, 0, 6));
        center.setBorder(new EmptyBorder(8, 12, 4, 12));

        waveLeft  = new WaveformPanel("CLIENTE  (canal esquerdo — L)", COLOR_CLIENT);
        waveRight = new WaveformPanel("ANALISTA (canal direito  — R)", COLOR_ANALYST);

        MouseAdapter seek = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (cropMode) { cropIn = sampleAt(e); cropOut = -1; atualizarCrop(); }
                else          { haltKeepPosition(); seekTo(e); }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (cropMode) { cropOut = sampleAt(e); atualizarCrop(); }
                else          { seekTo(e); }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (cropMode) { cropOut = sampleAt(e); normalizeCrop(); atualizarCrop(); }
                else          { seekTo(e); startPlayback(); }
            }
        };
        waveLeft.addMouseListener(seek);  waveLeft.addMouseMotionListener(seek);
        waveRight.addMouseListener(seek); waveRight.addMouseMotionListener(seek);

        center.add(waveLeft);
        center.add(waveRight);
        add(center, BorderLayout.CENTER);

        // ── Rodapé ──────────────────────────────────────────────────────────
        JPanel footer = mkPanel(BG_CONTROL);
        footer.setLayout(new BorderLayout(0, 6));
        footer.setBorder(new EmptyBorder(10, 16, 6, 16));

        // Linha 1: transporte + modo + mutes
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1.setOpaque(false);

        btnPlay = mkButton("▶  Play", COLOR_PLAY, BG_CONTROL);
        btnPlay.setEnabled(false);
        btnPlay.addActionListener(e -> togglePlay());

        btnStop = mkButton("■  Stop", new Color(220, 90, 90), BG_CONTROL);
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> stopPlayback());

        btnModo = mkToggle("Modo: JUNTO", TEXT_DIM);
        btnModo.setEnabled(false);
        btnModo.addActionListener(e -> toggleModo());

        // Controles MODO JUNTO
        panelMuteJunto = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panelMuteJunto.setOpaque(false);
        btnMuteLeft  = mkToggle("Mutar Cliente",  COLOR_CLIENT);
        btnMuteRight = mkToggle("Mutar Analista", COLOR_ANALYST);
        btnMuteLeft.addActionListener(e  -> muteLeft.set(btnMuteLeft.isSelected()));
        btnMuteRight.addActionListener(e -> muteRight.set(btnMuteRight.isSelected()));
        panelMuteJunto.add(btnMuteLeft);
        panelMuteJunto.add(btnMuteRight);

        // Controles MODO SEPARADO
        panelSolo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panelSolo.setOpaque(false);
        panelSolo.setVisible(false);
        lblSolo = new JLabel("Ouvindo:");
        lblSolo.setForeground(TEXT_DIM);
        lblSolo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btnSolo = mkButton("● CLIENTE", COLOR_CLIENT, BG_CONTROL);
        btnSolo.addActionListener(e -> toggleSolo());
        panelSolo.add(lblSolo);
        panelSolo.add(btnSolo);

        row1.add(btnPlay);
        row1.add(btnStop);
        row1.add(Box.createHorizontalStrut(12));
        row1.add(btnModo);
        row1.add(Box.createHorizontalStrut(6));
        row1.add(panelMuteJunto);
        row1.add(panelSolo);

        // Linha 2: velocidade + tempo
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2.setOpaque(false);

        lblSpeed = new JLabel("Velocidade: 1.0×");
        lblSpeed.setForeground(TEXT_DIM);
        lblSpeed.setFont(new Font("SansSerif", Font.PLAIN, 12));

        speedSlider = new JSlider(50, 200, 100);
        speedSlider.setOpaque(false);
        speedSlider.setPreferredSize(new Dimension(170, 22));
        speedSlider.addChangeListener(e -> {
            speed = speedSlider.getValue() / 100f;
            lblSpeed.setText(String.format("Velocidade: %.1f×", speed));
        });

        // Ganho de reprodução. Aplicado só na saída de áudio (não no recorte).
        // amplitude_linear = 10^(dB/20). Máx +3 dB = ~1,41× amplitude (dobro da potência).
        lblGain = new JLabel("Ganho: +0 dB");
        lblGain.setForeground(TEXT_DIM);
        lblGain.setFont(new Font("SansSerif", Font.PLAIN, 12));

        gainSlider = new JSlider(-18, 3, 0);
        gainSlider.setOpaque(false);
        gainSlider.setPreferredSize(new Dimension(140, 22));
        gainSlider.addChangeListener(e -> {
            int db = gainSlider.getValue();
            gainLinear = (float) Math.pow(10.0, db / 20.0);
            lblGain.setText(String.format("Ganho: %+d dB", db));
        });

        lblTime = new JLabel("0:00 / 0:00");
        lblTime.setForeground(TEXT_PRIMARY);
        lblTime.setFont(new Font("Monospaced", Font.BOLD, 13));

        row2.add(lblSpeed);
        row2.add(speedSlider);
        row2.add(Box.createHorizontalStrut(16));
        row2.add(lblGain);
        row2.add(gainSlider);
        row2.add(Box.createHorizontalStrut(20));
        row2.add(lblTime);

        // Linha 3: recorte (estéreo: cliente esquerda / analista direita)
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row3.setOpaque(false);

        JLabel tagCrop = new JLabel("Recorte:");
        tagCrop.setForeground(TEXT_DIM);
        tagCrop.setFont(new Font("SansSerif", Font.PLAIN, 12));

        btnCropMode  = mkToggle("✂  Recortar", COLOR_PLAY);
        btnCropSave  = mkButton("Salvar (mp3)", COLOR_PLAY, BG_CONTROL);
        btnCropClear = mkButton("Limpar",       TEXT_DIM,   BG_CONTROL);
        btnCropMode.setEnabled(false);
        btnCropSave.setEnabled(false);
        btnCropClear.setEnabled(false);
        btnCropMode.addActionListener(e  -> toggleCropMode());
        btnCropSave.addActionListener(e  -> salvarCrop());
        btnCropClear.addActionListener(e -> limparCrop());

        lblCrop = new JLabel("início --:-- · fim --:--");
        lblCrop.setForeground(TEXT_PRIMARY);
        lblCrop.setFont(new Font("Monospaced", Font.PLAIN, 12));

        row3.add(tagCrop);
        row3.add(btnCropMode);
        row3.add(btnCropSave);
        row3.add(btnCropClear);
        row3.add(Box.createHorizontalStrut(10));
        row3.add(lblCrop);

        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
        rows.setOpaque(false);
        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        footer.add(rows, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = mkPanel(new Color(14, 14, 20));
        statusBar.setLayout(new BorderLayout(8, 0));
        statusBar.setBorder(new EmptyBorder(4, 16, 4, 16));

        lblStatus = new JLabel("Pronto.");
        lblStatus.setForeground(TEXT_DIM);
        lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));

        loadBar = new JProgressBar();
        loadBar.setVisible(false);
        loadBar.setPreferredSize(new Dimension(160, 10));
        loadBar.setForeground(COLOR_PLAY);
        loadBar.setBackground(BG_DARK);

        statusBar.add(lblStatus, BorderLayout.CENTER);
        statusBar.add(loadBar,   BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(footer,    BorderLayout.CENTER);
        south.add(statusBar, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODO JUNTO / SEPARADO
    // ═══════════════════════════════════════════════════════════════════════
    private void toggleModo() {
        modoSeparado = btnModo.isSelected();
        if (modoSeparado) {
            btnModo.setText("Modo: SEPARADO");
            panelMuteJunto.setVisible(false);
            panelSolo.setVisible(true);
            muteLeft.set(false);  btnMuteLeft.setSelected(false);
            muteRight.set(false); btnMuteRight.setSelected(false);
            atualizarDestaqueSolo();
        } else {
            btnModo.setText("Modo: JUNTO");
            panelMuteJunto.setVisible(true);
            panelSolo.setVisible(false);
            waveLeft.setDimmed(false);
            waveRight.setDimmed(false);
        }
        if (leftSrc != null) startPlayback();   // retoma automaticamente
    }

    private void toggleSolo() {
        soloAnalista = !soloAnalista;
        atualizarDestaqueSolo();
        if (leftSrc != null) startPlayback();   // retoma automaticamente
    }

    private void atualizarDestaqueSolo() {
        if (soloAnalista) {
            btnSolo.setText("● ANALISTA");
            btnSolo.setForeground(COLOR_ANALYST);
            btnSolo.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_ANALYST.darker(), 1),
                new EmptyBorder(6, 14, 6, 14)));
            waveLeft.setDimmed(true);
            waveRight.setDimmed(false);
        } else {
            btnSolo.setText("● CLIENTE");
            btnSolo.setForeground(COLOR_CLIENT);
            btnSolo.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_CLIENT.darker(), 1),
                new EmptyBorder(6, 14, 6, 14)));
            waveLeft.setDimmed(false);
            waveRight.setDimmed(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ARQUIVO
    // ═══════════════════════════════════════════════════════════════════════
    private void openFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Selecionar gravação de chamada");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Áudio (*.mp3, *.mp4, *.m4a, *.wav)", "mp3", "mp4", "m4a", "wav"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        loadFile(fc.getSelectedFile());
    }

    private void loadFile(File f) {
        stopPlayback();
        status("Extraindo canais via ffmpeg...", true);
        btnPlay.setEnabled(false);
        btnModo.setEnabled(false);
        lblFile.setText(f.getName());

        SwingWorker<Void, String> w = new SwingWorker<>() {
            @Override protected Void doInBackground() throws Exception {
                publish("Criando arquivos temporários...");
                tmpLeft  = File.createTempFile("cm_L_", ".pcm");
                tmpRight = File.createTempFile("cm_R_", ".pcm");
                tmpLeft.deleteOnExit();
                tmpRight.deleteOnExit();

                publish("Extraindo canal esquerdo (cliente)...");
                ffmpeg(f, tmpLeft,  "pan=mono|c0=FL");

                publish("Extraindo canal direito (analista)...");
                ffmpeg(f, tmpRight, "pan=mono|c0=FR");

                publish("Abrindo fontes PCM...");
                // O ffmpeg produz PCM cru (s16le, 44100 Hz, mono): o formato é
                // fixo e conhecido, então não há cabeçalho para parsear e o
                // offset em bytes é simplesmente sampleIndex*2.
                closeSources();   // libera fontes de um carregamento anterior
                sampleRate   = 44100;
                monoFmt      = new AudioFormat(sampleRate, 16, 1, true, false);
                leftSrc      = new PcmSource(tmpLeft);
                rightSrc     = new PcmSource(tmpRight);
                totalSamples = (int) Math.min(leftSrc.totalSamples, rightSrc.totalSamples);
                position.set(0);
                return null;
            }
            @Override protected void process(java.util.List<String> c) {
                if (!c.isEmpty()) status(c.get(c.size()-1), true);
            }
            @Override protected void done() {
                try {
                    get();
                    waveLeft.setPcm(tmpLeft,  sampleRate);
                    waveRight.setPcm(tmpRight, sampleRate);
                    btnPlay.setEnabled(true);
                    btnModo.setEnabled(true);
                    btnCropMode.setEnabled(true);
                    btnCropClear.setEnabled(true);
                    limparCrop();
                    status("Pronto — duração: " + fmtTime(totalSamples), false);
                } catch (Exception ex) {
                    status("Erro: " + ex.getMessage(), false);
                    JOptionPane.showMessageDialog(CallMonitor.this,
                        "Falha ao carregar arquivo:\n" + ex.getMessage(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        w.execute();
    }

    // Usa SOMENTE o ffmpeg embutido, na mesma pasta do .jar. Não procura no PATH.
    // (O fallback para o nome puro só existe para rodar em desenvolvimento, quando
    //  se executa a partir dos .class sem um exe ao lado.)
    private static String ffmpegResolvido;   // cache da escolha

    private static synchronized String ffmpegCmd() {
        if (ffmpegResolvido != null) return ffmpegResolvido;

        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = win ? "ffmpeg.exe" : "ffmpeg";
        try {
            java.net.URI uri = CallMonitor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            File loc = new File(uri);
            File dir = loc.isFile() ? loc.getParentFile() : loc;
            File bundled = new File(dir, exe);
            if (bundled.isFile()) { ffmpegResolvido = bundled.getAbsolutePath(); return ffmpegResolvido; }
        } catch (Exception ignored) { }

        ffmpegResolvido = exe;   // fallback de desenvolvimento
        return ffmpegResolvido;
    }

    // Testa se um ffmpeg roda de fato (não só se o arquivo existe).
    private static boolean ffmpegFunciona(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ffmpeg(File input, File output, String filter) throws IOException, InterruptedException {
        // Saída em PCM cru s16le (sem cabeçalho WAV): assim o arquivo é só
        // amostras e o offset em bytes = sampleIndex*2, sem parsear cabeçalho.
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegCmd(), "-y", "-i", input.getAbsolutePath(),
            "-af", filter,
            "-ar", "44100",
            "-ac", "1",
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            output.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        int code = p.waitFor();
        if (code != 0) throw new IOException("ffmpeg erro " + code + " (filtro: " + filter + ")");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SEEK
    // ═══════════════════════════════════════════════════════════════════════
    private void seekTo(MouseEvent e) {
        if (totalSamples == 0) return;
        int w = e.getComponent().getWidth();
        if (w == 0) return;
        float pct = Math.max(0, Math.min(1f, (float) e.getX() / w));
        position.set((int)(pct * totalSamples));
        // o cursor é atualizado pelo timer de refreshProgress; o play ocorre ao soltar
        waveLeft.setProgress(pct);
        waveRight.setProgress(pct);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORTE (CROP)
    // Modo de seleção: ao ativar, pausa a chamada e mantém a posição parada.
    // A seleção é feita arrastando sobre o waveform e NÃO move o cursor.
    // Exporta em estéreo (cliente na esquerda, analista na direita), em MP3,
    // cru: sem ganho e sem mudança de velocidade.
    // ═══════════════════════════════════════════════════════════════════════
    private void toggleCropMode() {
        cropMode = btnCropMode.isSelected();
        if (cropMode) {
            pausePlayback();   // pausa sem zerar a posição
            status("Modo recorte: arraste sobre o waveform para marcar início e fim. "
                 + "A posição da reprodução não muda.", false);
        } else {
            status("Modo recorte desativado.", false);
        }
    }

    private int sampleAt(MouseEvent e) {
        int w = e.getComponent().getWidth();
        if (w == 0 || totalSamples == 0) return 0;
        float pct = Math.max(0, Math.min(1f, (float) e.getX() / w));
        return (int)(pct * totalSamples);
    }

    private void normalizeCrop() {
        if (cropIn >= 0 && cropOut >= 0 && cropOut < cropIn) {
            int t = cropIn; cropIn = cropOut; cropOut = t;
        }
    }

    private void limparCrop() {
        cropIn = -1; cropOut = -1;
        atualizarCrop();
    }

    private void atualizarCrop() {
        // Exibe na ordem correta mesmo durante um arraste para a esquerda.
        int a = cropIn, b = cropOut;
        if (a >= 0 && b >= 0 && b < a) { int t = a; a = b; b = t; }
        lblCrop.setText("início " + (a >= 0 ? fmtTime(a) : "--:--")
                      + " · fim " + (b >= 0 ? fmtTime(b) : "--:--"));
        btnCropSave.setEnabled(a >= 0 && b >= 0 && b > a);
        float pa = (a >= 0 && totalSamples > 0) ? (float) a / totalSamples : -1f;
        float pb = (b >= 0 && totalSamples > 0) ? (float) b / totalSamples : -1f;
        waveLeft.setSelection(pa, pb);
        waveRight.setSelection(pa, pb);
    }

    private void salvarCrop() {
        if (leftSrc == null || rightSrc == null) return;
        normalizeCrop();
        if (cropIn < 0 || cropOut < 0 || cropOut <= cropIn) return;

        // Limita o fim ao que existe nos dois canais
        int maxSamples = (int) Math.min(leftSrc.totalSamples, rightSrc.totalSamples);
        final int s0 = cropIn;
        final int s1 = Math.min(cropOut, maxSamples);
        if (s1 <= s0) return;

        // Lê o modo atual: JUNTO → estéreo; SEPARADO → só o canal ouvido.
        final boolean sep     = modoSeparado;
        final boolean analyst = soloAnalista;
        String sugestao = sep ? (analyst ? "recorte_analista.mp3" : "recorte_cliente.mp3")
                              : "recorte_estereo.mp3";

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Salvar recorte (MP3)");
        fc.setSelectedFile(new File(sugestao));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = fc.getSelectedFile();
        if (!chosen.getName().toLowerCase().endsWith(".mp3"))
            chosen = new File(chosen.getAbsoluteFile().getParentFile(), chosen.getName() + ".mp3");

        final File dest = chosen;
        status("Exportando recorte em MP3...", true);

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                int n = s1 - s0;
                AudioFormat fmt;
                InputStream pcm;

                // O PCM do recorte é lido DIRETO dos arquivos de canal em disco,
                // em blocos (InterleavedPcmStream), sem copiar o trecho para a
                // RAM. Assim mesmo uma seleção de horas exporta sem estourar.
                if (sep) {
                    // Separado: mono do canal ouvido (analista = direita, cliente = esquerda)
                    File ch = analyst ? tmpRight : tmpLeft;
                    fmt = new AudioFormat(sampleRate, 16, 1, true, false);
                    pcm = new InterleavedPcmStream(ch, null, s0, n);
                } else {
                    // Junto: estéreo, esquerda = cliente, direita = analista
                    fmt = new AudioFormat(sampleRate, 16, 2, true, false);
                    pcm = new InterleavedPcmStream(tmpLeft, tmpRight, s0, n);
                }

                // WAV temporário → MP3 via ffmpeg (libmp3lame, VBR ~190 kbps).
                // Cru: sem ganho e sem mudança de velocidade.
                File tmpWav = File.createTempFile("cm_crop_", ".wav");
                tmpWav.deleteOnExit();
                try (AudioInputStream ais = new AudioInputStream(pcm, fmt, n)) {
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tmpWav);
                }

                ProcessBuilder pb = new ProcessBuilder(
                    ffmpegCmd(), "-y", "-i", tmpWav.getAbsolutePath(),
                    "-codec:a", "libmp3lame", "-q:a", "2",
                    dest.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                int code = p.waitFor();
                tmpWav.delete();
                if (code != 0) throw new IOException("ffmpeg retornou código " + code);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    String tipo = sep ? (analyst ? "analista (mono)" : "cliente (mono)")
                                      : "estéreo";
                    status("Recorte salvo: " + dest.getName()
                            + " (" + fmtTime(s1 - s0) + ", " + tipo + ")", false);
                } catch (Exception ex) {
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    status("Erro ao salvar recorte: " + cause.getMessage(), false);
                    JOptionPane.showMessageDialog(CallMonitor.this,
                        "Falha ao salvar recorte:\n" + cause.getMessage(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLAYBACK
    // ═══════════════════════════════════════════════════════════════════════
    private void togglePlay() {
        if (playing.get()) pausePlayback(); else startPlayback();
    }

    private void startPlayback() {
        if (leftSrc == null) return;
        haltKeepPosition();          // encerra reprodução anterior sem zerar a posição
        playing.set(true);
        btnPlay.setText("⏸  Pausar");
        btnStop.setEnabled(true);

        // Referências locais das fontes para o thread de reprodução (evita corrida
        // com um recarregamento e o overhead de reler os campos no loop interno).
        final PcmSource srcL = leftSrc;
        final PcmSource srcR = rightSrc;

        playThread = new Thread(() -> {
            try {
                log("play: leftSrc=" + srcL.totalSamples
                    + " rightSrc=" + srcR.totalSamples
                    + " sampleRate=" + sampleRate + " totalSamples=" + totalSamples
                    + " pos=" + position.get());
                AudioFormat stereoFmt = new AudioFormat(sampleRate, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, stereoFmt);
                log("abrindo linha de audio (" + sampleRate + "/16/estereo)...");
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(stereoFmt, stereoFmt.getFrameSize() * (sampleRate / 2));
                line.start();
                log("linha de audio aberta; iniciando reproducao");

                // As amostras são lidas DIRETO do PCM em disco (srcL/srcR), via
                // sampleAt(), que mantém só uma pequena janela deslizante na RAM.
                // Antes o código carregava os dois canais inteiros em byte[], o
                // que estourava a memória em arquivos longos (chamadas de ~1h+).
                final int len = (int) Math.min(srcL.totalSamples, srcR.totalSamples);

                if (len < 1024) {   // FRAME — áudio vazio ou não carregado
                    log("reproducao abortada: len=" + len + " (audio vazio ou curto)");
                    SwingUtilities.invokeLater(() -> {
                        status("Áudio não carregado ou muito curto.", false);
                        JOptionPane.showMessageDialog(this,
                            "O áudio não foi carregado corretamente (vazio ou muito curto).\n" +
                            "Carregue um arquivo válido antes de tocar.",
                            "Sem áudio", JOptionPane.WARNING_MESSAGE);
                    });
                    return;
                }

                // ── Parâmetros do WSOLA ─────────────────────────────────────────
                // FRAME : janela de análise/síntese (~23 ms a 44,1 kHz)
                // HS    : passo de síntese (saída). Fixo. Sobreposição de 50%.
                // SEEK  : raio de busca. Cobre ao menos um período de pitch da voz
                //         mais grave (~80 Hz ≈ 12 ms) para a correlação encontrar
                //         o deslocamento em que as ondas se alinham. É isso que
                //         elimina o som metálico/robotizado do OLA simples.
                final int FRAME = 1024;
                final int HS    = FRAME / 2;
                final int SEEK  = sampleRate / 100;              // ~10 ms (≈441)
                final float[] window = hanningWindow(FRAME);

                // Cauda de sobreposição carregada de um quadro para o próximo
                float[] tailL = new float[HS];
                float[] tailR = new float[HS];
                // Mistura L+R do trecho de referência, calculada uma vez por quadro
                float[] refBuf = new float[HS];

                byte[] lineBuf = new byte[HS * 4];               // estéreo 16-bit

                double  nominal = position.get();   // posição nominal de análise (avança por Ha)
                int     prevPos = (int) nominal;     // posição realmente lida no quadro anterior
                boolean first   = true;

                while (playing.get()) {
                    // Passo de análise depende da velocidade atual (lida ao vivo).
                    // Ha > HS  → consome mais original por quadro → toca mais rápido.
                    // O pitch NÃO muda: o quadro de saída tem sempre HS amostras.
                    double ha = HS * (double) speed;

                    int pos;
                    if (first) {
                        pos   = (int) nominal;
                        first = false;
                    } else {
                        int base = (int) Math.round(nominal);
                        // Referência: a continuação "natural" do quadro anterior,
                        // ou seja, o que viria HS amostras depois do ponto já lido.
                        int refStart = prevPos + HS;
                        // Procura o deslocamento que melhor alinha a forma de onda
                        // do candidato com essa referência (correlação normalizada).
                        int   bestDelta = 0;
                        float bestScore = -Float.MAX_VALUE;
                        if (refStart + HS < len) {
                            // mistura L+R da referência, calculada uma vez por quadro
                            for (int i = 0; i < HS; i += 2)
                                refBuf[i] = srcL.sampleAt(refStart + i) + srcR.sampleAt(refStart + i);
                            for (int d = -SEEK; d <= SEEK; d++) {
                                int cand = base + d;
                                if (cand < 0 || cand + HS >= len) continue;
                                float num = 0f, energy = 0f;
                                for (int i = 0; i < HS; i += 2) {     // passo 2 = busca 2× mais rápida
                                    float a = srcL.sampleAt(cand + i) + srcR.sampleAt(cand + i);
                                    num    += a * refBuf[i];
                                    energy += a * a;
                                }
                                float score = num / (float) Math.sqrt(energy + 1e-6f);
                                if (score > bestScore) { bestScore = score; bestDelta = d; }
                            }
                        }
                        pos = base + bestDelta;
                    }

                    if (pos < 0) pos = 0;
                    if (pos + FRAME >= len) { playing.set(false); break; }

                    // Overlap-add:
                    //   saída[i] = cauda anterior + início do quadro atual (janelado)
                    //   nova cauda = segunda metade do quadro atual (janelado)
                    float g = gainLinear;
                    for (int i = 0; i < HS; i++) {
                        float outL = tailL[i] + srcL.sampleAt(pos + i) * window[i];
                        float outR = tailR[i] + srcR.sampleAt(pos + i) * window[i];

                        short sL = floatToShort(outL * g);
                        short sR = floatToShort(outR * g);
                        if (!modoSeparado) {
                            writeSample(lineBuf, i*4,   muteLeft.get()  ? (short)0 : sL);
                            writeSample(lineBuf, i*4+2, muteRight.get() ? (short)0 : sR);
                        } else {
                            short mono = soloAnalista ? sR : sL;
                            writeSample(lineBuf, i*4,   mono);
                            writeSample(lineBuf, i*4+2, mono);
                        }

                        tailL[i] = srcL.sampleAt(pos + HS + i) * window[HS + i];
                        tailR[i] = srcR.sampleAt(pos + HS + i) * window[HS + i];
                    }
                    line.write(lineBuf, 0, lineBuf.length);

                    prevPos  = pos;
                    nominal += ha;
                    position.set((int) Math.min(nominal, totalSamples));
                }

                line.drain();

            } catch (Throwable ex) {
                logEx("erro na reproducao", ex);
                if (playing.get())
                    SwingUtilities.invokeLater(() -> {
                        status("Erro na reprodução: " + ex, false);
                        JOptionPane.showMessageDialog(this,
                            "Não foi possível reproduzir o áudio:\n" + ex,
                            "Erro de reprodução", JOptionPane.ERROR_MESSAGE);
                    });
            } finally {
                playing.set(false);
                closeLine();
                SwingUtilities.invokeLater(() -> {
                    btnPlay.setText("▶  Play");
                    if (position.get() >= totalSamples) {
                        position.set(0);
                        status("Reprodução concluída.", false);
                    }
                });
            }
        }, "playback");
        playThread.setDaemon(true);
        playThread.start();
    }

    // Janela de Hanning — suaviza bordas das janelas OLA para evitar cliques na junção
    private float[] hanningWindow(int size) {
        float[] w = new float[size];
        for (int i = 0; i < size; i++)
            w[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1))));
        return w;
    }

    private short floatToShort(float f) {
        float clamped = Math.max(-1f, Math.min(1f, f));
        return (short)(clamped * 32767f);
    }

    // Escreve um sample 16-bit little-endian no buffer de saída
    private void writeSample(byte[] buf, int byteOffset, short value) {
        buf[byteOffset]   = (byte)(value & 0xFF);
        buf[byteOffset+1] = (byte)((value >> 8) & 0xFF);
    }

    private void pausePlayback() {
        playing.set(false);
        btnPlay.setText("▶  Play");
        // flush() descarta dados que ainda estão no buffer interno da linha,
        // evitando que após um seek o áudio antigo seja reproduzido antes do novo
        closeLine();
        joinPlayThread();
    }

    // Encerra a thread de reprodução atual mantendo a posição (usado antes de
    // reiniciar em seek, troca de modo ou solo). Garante que a thread antiga
    // morreu antes que startPlayback abra uma nova linha, evitando corrida.
    private void haltKeepPosition() {
        playing.set(false);
        closeLine();
        joinPlayThread();
    }

    private void joinPlayThread() {
        Thread t = playThread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(300); } catch (InterruptedException ignored) {}
        }
    }

    private void closeLine() {
        if (line != null && line.isOpen()) {
            line.stop();
            line.flush();   // descarta buffer pendente — essencial para seek limpo
            line.close();
        }
    }

    private void stopPlayback() {
        playing.set(false);
        closeLine();
        joinPlayThread();
        position.set(0);
        SwingUtilities.invokeLater(() -> {
            btnPlay.setText("▶  Play");
            btnStop.setEnabled(false);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════
    private String fmtTime(int samples) {
        if (sampleRate == 0) return "0:00";
        int s = samples / sampleRate;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void status(String msg, boolean loading) {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText(msg);
            loadBar.setVisible(loading);
            loadBar.setIndeterminate(loading);
        });
    }

    private void cleanup() {
        stopPlayback();
        closeSources();   // fecha os RandomAccessFile antes de apagar (necessário no Windows)
        if (tmpLeft  != null) tmpLeft.delete();
        if (tmpRight != null) tmpRight.delete();
    }

    // Fecha as fontes PCM em disco (idempotente). Chamado ao recarregar e ao sair.
    private void closeSources() {
        if (leftSrc  != null) { try { leftSrc.close();  } catch (IOException ignored) {} leftSrc  = null; }
        if (rightSrc != null) { try { rightSrc.close(); } catch (IOException ignored) {} rightSrc = null; }
    }

    // ── Factories ───────────────────────────────────────────────────────────
    private static JPanel mkPanel(Color bg) {
        JPanel p = new JPanel(); p.setBackground(bg); return p;
    }

    private static JButton mkButton(String txt, Color fg, Color bg) {
        JButton b = new JButton(txt);
        b.setForeground(fg); b.setBackground(bg);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(fg.darker(), 1),
            new EmptyBorder(6, 14, 6, 14)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        Color hov = bg.brighter();
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(hov); }
            @Override public void mouseExited(MouseEvent e)  { b.setBackground(bg); }
        });
        return b;
    }

    private static JToggleButton mkToggle(String txt, Color accent) {
        JToggleButton b = new JToggleButton(txt);
        b.setForeground(TEXT_DIM); b.setBackground(BG_CONTROL);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent.darker(), 1),
            new EmptyBorder(5, 12, 5, 12)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFont(new Font("SansSerif", Font.PLAIN, 12));
        b.addChangeListener(e -> {
            if (b.isSelected()) { b.setForeground(accent); b.setBackground(accent.darker().darker()); }
            else                { b.setForeground(TEXT_DIM); b.setBackground(BG_CONTROL); }
        });
        return b;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FONTE PCM EM DISCO (acesso aleatório com janela deslizante)
    // ═══════════════════════════════════════════════════════════════════════
    // Em vez de manter o áudio inteiro na RAM, lê amostras 16-bit LE sob demanda
    // de um arquivo PCM cru (s16le mono). Uma janela contígua de poucos MB é
    // mantida em memória e recarregada quando o índice pedido cai fora dela —
    // como a reprodução é quase sequencial (com busca WSOLA de só ±~441
    // amostras), os recarregamentos são raros. Uso de RAM constante, mesmo em
    // chamadas de várias horas. Não é thread-safe: cada consumidor usa o seu.
    static final class PcmSource implements Closeable {
        private static final int CAPACITY = 4 << 20;   // 4 MB ≈ 2M amostras ≈ 47 s

        private final RandomAccessFile raf;
        final long  totalSamples;
        private final byte[] window = new byte[CAPACITY];
        private long windowStartSample = 0;
        private int  windowValidBytes  = -1;           // -1 = janela ainda não carregada

        PcmSource(File pcmFile) throws IOException {
            this.raf = new RandomAccessFile(pcmFile, "r");
            this.totalSamples = raf.length() / 2;
        }

        // Lê uma amostra (16-bit LE). Retorna 0 fora dos limites do arquivo.
        short readSample(long sampleIndex) {
            if (sampleIndex < 0 || sampleIndex >= totalSamples) return 0;
            int off = bufferOffset(sampleIndex);
            int lo = window[off]     & 0xFF;
            int hi = window[off + 1];                  // byte alto (com sinal)
            return (short)((hi << 8) | lo);
        }

        // Amostra normalizada para [-1, 1], usada pelo WSOLA.
        float sampleAt(long sampleIndex) {
            return readSample(sampleIndex) / 32768f;
        }

        // Garante que a amostra esteja na janela e devolve o offset do seu byte baixo.
        private int bufferOffset(long sampleIndex) {
            long byteIndex = sampleIndex * 2;
            // Precisa dos 2 bytes da amostra dentro da janela carregada.
            if (windowValidBytes < 0
                    || byteIndex < windowStartSample * 2
                    || byteIndex + 1 >= windowStartSample * 2 + windowValidBytes) {
                refill(sampleIndex);
            }
            return (int)(byteIndex - windowStartSample * 2);
        }

        // Recarrega a janela começando um pouco antes da amostra pedida (margem
        // para cobrir a busca WSOLA para trás sem refazer leitura logo em seguida).
        private void refill(long sampleIndex) {
            long margin = (CAPACITY / 2) / 8;          // ~1/8 da capacidade, em amostras
            long start  = Math.max(0, sampleIndex - margin);
            try {
                raf.seek(start * 2);
                int n = 0;
                while (n < window.length) {
                    int r = raf.read(window, n, window.length - n);
                    if (r < 0) break;
                    n += r;
                }
                windowStartSample = start;
                windowValidBytes  = Math.max(n, 0);
            } catch (IOException e) {
                throw new RuntimeException("Falha ao ler PCM do disco", e);
            }
        }

        @Override public void close() throws IOException { raf.close(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STREAM DE RECORTE (PCM intercalado lido do disco, em blocos)
    // ═══════════════════════════════════════════════════════════════════════
    // Produz o PCM do trecho selecionado lendo direto do(s) arquivo(s) de canal,
    // sem copiar a seleção para a RAM. Mono: copia um canal. Estéreo: intercala
    // L e R (LRLR). Usado como fonte de um AudioInputStream na exportação.
    static final class InterleavedPcmStream extends InputStream {
        private final RandomAccessFile a;              // canal esquerdo (ou único)
        private final RandomAccessFile b;              // canal direito; null = mono
        private final boolean stereo;
        private long remainingSamples;
        private final byte[] frame;                    // amostra montada (2 ou 4 bytes)
        private int framePos;
        private final byte[] bufA, bufB;               // buffers de bloco por canal
        private int posA, lenA, posB, lenB;

        InterleavedPcmStream(File fa, File fb, long startSample, long sampleCount) throws IOException {
            this.stereo = (fb != null);
            this.a = new RandomAccessFile(fa, "r");
            this.a.seek(startSample * 2);
            if (stereo) { this.b = new RandomAccessFile(fb, "r"); this.b.seek(startSample * 2); }
            else        { this.b = null; }
            this.remainingSamples = sampleCount;
            this.frame    = new byte[stereo ? 4 : 2];
            this.framePos = frame.length;              // vazio: força preencher no 1º read
            this.bufA = new byte[1 << 16];
            this.bufB = stereo ? new byte[1 << 16] : null;
        }

        private int nextByteA() throws IOException {
            if (posA >= lenA) { lenA = a.read(bufA); posA = 0; if (lenA <= 0) return -1; }
            return bufA[posA++] & 0xFF;
        }
        private int nextByteB() throws IOException {
            if (posB >= lenB) { lenB = b.read(bufB); posB = 0; if (lenB <= 0) return -1; }
            return bufB[posB++] & 0xFF;
        }

        private boolean fillFrame() throws IOException {
            if (remainingSamples <= 0) return false;
            int a0 = nextByteA(), a1 = nextByteA();
            if (a0 < 0 || a1 < 0) { remainingSamples = 0; return false; }
            frame[0] = (byte) a0; frame[1] = (byte) a1;
            if (stereo) {
                int b0 = nextByteB(), b1 = nextByteB();   // canal mais curto → silêncio
                frame[2] = (byte)(b0 < 0 ? 0 : b0);
                frame[3] = (byte)(b1 < 0 ? 0 : b1);
            }
            framePos = 0;
            remainingSamples--;
            return true;
        }

        @Override public int read(byte[] out, int off, int len) throws IOException {
            int produced = 0;
            while (produced < len) {
                if (framePos >= frame.length && !fillFrame()) break;
                out[off + produced++] = frame[framePos++];
            }
            return produced == 0 ? -1 : produced;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override public void close() throws IOException {
            try { a.close(); } finally { if (b != null) b.close(); }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PAINEL DE WAVEFORM
    // ═══════════════════════════════════════════════════════════════════════
    static class WaveformPanel extends JPanel {

        private final String label;
        private final Color  color;
        private float[]      peaks;
        private float        progress;
        private boolean      dimmed;
        private float        selStart = -1f;   // pct do início do recorte (-1 = sem marca)
        private float        selEnd   = -1f;   // pct do fim do recorte

        WaveformPanel(String label, Color color) {
            this.label = label; this.color = color;
            setBackground(BG_PANEL);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 55, 75), 1),
                new EmptyBorder(4, 4, 4, 4)));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        // Lê o PCM cru (s16le mono) do arquivo em disco em blocos sequenciais,
        // agregando o pico por faixa de pixels. Não carrega o áudio na RAM (só o
        // float[] de picos) e publica resultados parciais para a timeline ir
        // aparecendo progressivamente em arquivos longos.
        void setPcm(File pcmFile, int sampleRate) {
            final int px = Math.max(getWidth(), 900);
            new SwingWorker<Void, float[]>() {
                @Override protected Void doInBackground() throws Exception {
                    float[] p = new float[px];
                    long total = pcmFile.length() / 2;
                    if (total <= 0) { publish(p); return null; }
                    double spp = (double) total / px;
                    long publishStep = Math.max(1, total / 60);
                    long nextPublish = publishStep;

                    try (InputStream in = new BufferedInputStream(
                            new FileInputStream(pcmFile), 1 << 16)) {
                        byte[] buf = new byte[1 << 16];
                        long sampleIdx = 0;
                        int  curBucket = 0;
                        float curMax   = 0;
                        boolean haveLo = false;
                        int  loByte    = 0;
                        int  read;
                        while ((read = in.read(buf)) > 0) {
                            for (int i = 0; i < read; i++) {
                                if (!haveLo) { loByte = buf[i] & 0xFF; haveLo = true; continue; }
                                short v = (short)((buf[i] << 8) | loByte);   // buf[i] = byte alto (com sinal)
                                haveLo = false;
                                float a = Math.abs(v / 32768f);
                                int bucket = (int)(sampleIdx / spp);
                                if (bucket >= px) bucket = px - 1;
                                if (bucket != curBucket) { p[curBucket] = curMax; curBucket = bucket; curMax = 0; }
                                if (a > curMax) curMax = a;
                                sampleIdx++;
                                if (sampleIdx >= nextPublish) {
                                    p[curBucket] = curMax;
                                    publish(p.clone());
                                    nextPublish += publishStep;
                                }
                            }
                        }
                        p[curBucket] = curMax;
                    }
                    publish(p);
                    return null;
                }
                @Override protected void process(java.util.List<float[]> chunks) {
                    peaks = chunks.get(chunks.size() - 1);
                    repaint();
                }
            }.execute();
        }

        void setProgress(float pct) { this.progress = pct; repaint(); }
        void setDimmed(boolean d)   { this.dimmed = d; repaint(); }
        void setSelection(float startPct, float endPct) {
            this.selStart = startPct; this.selEnd = endPct; repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight(), mid = h / 2;

            g2.setColor(BG_PANEL);
            g2.fillRect(0, 0, w, h);

            // Label com estado
            Color labelColor = dimmed ? TEXT_DIM.darker() : color;
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            g2.setColor(labelColor);
            g2.drawString(label + (dimmed ? "  [inativo]" : ""), 8, 15);

            // Linha central
            g2.setColor(new Color(60, 60, 80));
            g2.drawLine(0, mid, w, mid);

            if (peaks == null) {
                g2.setColor(new Color(50, 50, 68));
                g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
                String msg = "Carregue um arquivo para visualizar o waveform";
                int mw = g2.getFontMetrics().stringWidth(msg);
                g2.drawString(msg, (w - mw) / 2, mid + 5);
                g2.dispose(); return;
            }

            int   playedX = (int)(progress * w);
            float alpha   = dimmed ? 0.22f : 1.0f;
            Color bright  = applyAlpha(color, alpha);
            Color dark    = applyAlpha(color.darker().darker(), alpha);

            for (int px = 0; px < w; px++) {
                int pi   = Math.min((int)((float)px / w * peaks.length), peaks.length - 1);
                int barH = (int)(peaks[pi] * (mid - 10));
                g2.setColor(px < playedX ? bright : dark);
                g2.drawLine(px, mid - barH, px, mid + barH);
            }

            // Região de recorte (marcadores início/fim)
            int selA = selStart >= 0 ? (int)(selStart * w) : -1;
            int selB = selEnd   >= 0 ? (int)(selEnd   * w) : -1;
            if (selA >= 0 && selB >= 0 && selB > selA) {
                g2.setColor(new Color(255, 200, 60, 40));
                g2.fillRect(selA, 0, selB - selA, h);
            }
            g2.setStroke(new BasicStroke(1.5f));
            if (selA >= 0) { g2.setColor(new Color(255, 200, 60, 220)); g2.drawLine(selA, 0, selA, h); }
            if (selB >= 0) { g2.setColor(new Color(255, 200, 60, 220)); g2.drawLine(selB, 0, selB, h); }

            // Cursor de posição (só no canal ativo)
            if (!dimmed) {
                g2.setColor(CURSOR_CLR);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(playedX, 0, playedX, h);
                int[] tx = {playedX-5, playedX+5, playedX};
                int[] ty = {0, 0, 9};
                g2.fillPolygon(tx, ty, 3);
            }

            g2.dispose();
        }

        private Color applyAlpha(Color c, float a) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(255 * a));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        log("==== CallMonitor iniciado ====");
        log("Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name"));
        log("ffmpeg: " + ffmpegCmd());
        dumpAudioInfo();
        if (!ffmpegFunciona(ffmpegCmd())) {
            JOptionPane.showMessageDialog(null,
                "ffmpeg não encontrado.\n\n" +
                "Na instalação padrão ele vem embutido junto do programa.\n" +
                "Se estiver rodando avulso, coloque o ffmpeg ao lado do .jar\n" +
                "ou instale e adicione ao PATH (https://ffmpeg.org/download.html).",
                "ffmpeg ausente", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new CallMonitor().setVisible(true));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DIAGNÓSTICO — grava um log em  <pasta do usuário>\callmonitor.log
    // ═══════════════════════════════════════════════════════════════════════
    static void log(String msg) {
        try (FileWriter w = new FileWriter(
                new File(System.getProperty("user.home"), "callmonitor.log"), true)) {
            w.write(new java.util.Date() + "  " + msg + System.lineSeparator());
        } catch (Exception ignored) { }
    }

    static void logEx(String contexto, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(contexto + ": " + sw);
    }

    static void dumpAudioInfo() {
        try {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            log("Mixers de audio: " + mixers.length);
            for (Mixer.Info mi : mixers) {
                Mixer mx = AudioSystem.getMixer(mi);
                boolean temSaida = mx.getSourceLineInfo().length > 0;
                log("  - " + mi.getName() + "  (saida: " + (temSaida ? "sim" : "nao") + ")");
            }
            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            log("Linha 44100/16/estereo suportada: " + AudioSystem.isLineSupported(info));
        } catch (Throwable t) {
            logEx("dumpAudioInfo", t);
        }
    }
}