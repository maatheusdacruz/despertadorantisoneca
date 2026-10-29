# Despertador (celular + computador)

Um despertador em dois apps que só se desliga se você **levantar da cama**:

1. Na hora marcada o celular toca e avisa o computador pelo Wi-Fi.
2. O computador acende a tela e mostra, em tela cheia, um **QR novo** a cada alarme.
3. Você vai até o computador e escaneia o QR com o celular. Só então o celular mostra um **código de 6 dígitos**.
4. Você digita o código no teclado do computador. Ele valida e o celular para de tocar.
5. **Verificação:** 5 min depois o celular toca baixo (25 %) e você tem 2 min para repetir o ritual. Cumpriu: acabou. Não cumpriu: volume no máximo e, depois de desligado, nova verificação.

Tudo roda na rede local. Sem nuvem, sem contas.

```
despertador/
├── android/                 app Android (Kotlin) – ./gradlew assembleDebug
├── desktop/                 app do computador (Python 3 + tkinter)
│   ├── despertador_pc.py
│   ├── simular_celular.py   simula o celular para testar o PC
│   ├── test_protocolo.py
│   └── requirements.txt
└── protocol-vector.json     vetor de teste compartilhado (Python e Kotlin)
```

## Protocolo

| Item | Valor |
|---|---|
| Pareamento | QR do PC: `PAIR|<ip>:8765|<chave>`; chave = 32 bytes em hex, salva em `~/.despertador.json` |
| Aviso | `POST /ring` a cada 2 s com `X-Ts` (unix, s) e `X-Sig` = HMAC-SHA256(chave, `"<path>:<ts>"`) em hex. A chave entra como bytes UTF-8 da string hex |
| Resposta | `{"dismissed": bool}`; 403 se a assinatura for inválida ou o relógio divergir mais de 300 s |
| QR do alarme | `DESP:<nonce>` (16 bytes hex, igual enquanto a sessão durar) |
| Código | `HMAC-SHA256(chave, "code:<nonce>")` → 8 primeiros hex → inteiro `% 1_000_000` → 6 dígitos com zeros à esquerda |
| Após código certo | sessão encerra; `/ring` responde `dismissed:true` por 30 s |
| Watchdog | 90 s sem `/ring` durante o alarme → o PC fecha a tela de alarme |

> **Relógios:** celular e PC precisam estar com a hora certa (diferença máxima 300 s). Deixe a hora automática ligada nos dois.

## 1. Computador

Requisitos: Python 3.9+ com tkinter (no Linux: `sudo apt install python3-tk`).

```bash
cd desktop
python3 -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python despertador_pc.py            # --debug mostra o código esperado no terminal
```

Na primeira execução ele cria `~/.despertador.json` (chave secreta, permissão 600) e mostra a janela ociosa com o **QR de pareamento**. Fechar a janela só a minimiza.

Opções: `--debug`, `--port 8765`, `--ip <ip>` (força o IP que vai no QR), `--windowed` (alarme em janela comum, só para testes).

**Não deixe o computador bloqueado/suspenso.** Enquanto o programa está aberto ele impede a *suspensão* (Windows: `SetThreadExecutionState`; macOS: `caffeinate`; Linux: `systemd-inhibit`), mas a *tela* pode apagar e o alarme a acende. Porém **nenhum programa consegue aparecer por cima de uma tela de bloqueio** (senha) — desative o bloqueio automático de tela (ou o pedido de senha ao despertar) na configuração do sistema, senão o alarme não aparece e você cairá no plano B do celular. Fechar a tampa do notebook também pode suspender.

### Firewall: liberar a porta 8765 (rede privada)

- **Windows** (PowerShell como administrador):
  `New-NetFirewallRule -DisplayName "Despertador" -Direction Inbound -Protocol TCP -LocalPort 8765 -Profile Private -Action Allow`
  e confirme que a rede Wi-Fi está marcada como **Privada**.
- **macOS:** Ajustes › Rede › Firewall › Opções: permita conexões de entrada para o Python.
- **Linux:** `sudo ufw allow from 192.168.0.0/16 to any port 8765 proto tcp` (ajuste a faixa da sua rede).

### Reservar o IP do computador no roteador

O celular guarda o IP do PC no pareamento; se o IP mudar, o aviso falha. No painel do roteador (geralmente `192.168.0.1` ou `192.168.1.1`) procure *DHCP › Reserva de endereço / Address reservation* e associe o MAC do PC a um IP fixo. Se mudar o IP, pareie de novo.

### Iniciar junto com o sistema

- **Windows – Pasta de Inicialização:** `Win+R` › `shell:startup` › crie um atalho para
  `C:\caminho\desktop\.venv\Scripts\pythonw.exe C:\caminho\desktop\despertador_pc.py`.
  **Agendador de Tarefas:** *Criar Tarefa…* › Disparador *Ao fazer logon* › Ação *Iniciar programa* com o mesmo `pythonw.exe` e o script como argumento (marque “Executar somente quando o usuário estiver conectado”).
- **macOS – LaunchAgent:** salve em `~/Library/LaunchAgents/com.despertador.pc.plist`:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0"><dict>
    <key>Label</key><string>com.despertador.pc</string>
    <key>ProgramArguments</key><array>
      <string>/caminho/desktop/.venv/bin/python</string>
      <string>/caminho/desktop/despertador_pc.py</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
  </dict></plist>
  ```
  `launchctl load ~/Library/LaunchAgents/com.despertador.pc.plist`
- **Linux – serviço de usuário do systemd:** `~/.config/systemd/user/despertador.service`:
  ```ini
  [Unit]
  Description=Despertador (PC)
  After=graphical-session.target

  [Service]
  ExecStart=/caminho/desktop/.venv/bin/python /caminho/desktop/despertador_pc.py
  Restart=on-failure
  Environment=DISPLAY=:0

  [Install]
  WantedBy=graphical-session.target
  ```
  `systemctl --user daemon-reload && systemctl --user enable --now despertador`
  (No Wayland, às vezes é preciso também `Environment=WAYLAND_DISPLAY=wayland-0`.)
  **Alternativa – autostart:** crie `~/.config/autostart/despertador.desktop` com `Exec=/caminho/desktop/.venv/bin/python /caminho/desktop/despertador_pc.py`, `Type=Application`, `Name=Despertador`.

### Testar o PC sem celular

```bash
python despertador_pc.py --debug --windowed     # terminal 1
python simular_celular.py                       # terminal 2 (assina e envia /ring a cada 2 s)
```
O terminal 1 imprime o código esperado; digite-o na janela. O simulador para ao receber `dismissed:true`. `--bad-sig` testa o 403.

Sair de emergência da tela cheia: feche o celular/simulador (em 90 s a tela se fecha sozinha) ou `Ctrl+C` no terminal / encerre o processo.

## 2. Android

Requisitos: JDK 17+ e Android SDK (plataforma 37, build-tools 37). Crie `android/local.properties` com `sdk.dir=/caminho/do/Android/Sdk` (ou exporte `ANDROID_HOME`).

```bash
cd android
./gradlew assembleDebug          # gera app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # testes Kotlin
```

Requer **Google Play Services** (o leitor de QR é o Google code scanner, sem pedir permissão de câmera). minSdk 29, targetSdk 37 (Android 17).

### Instalar o APK via USB

1. No celular: Configurações › Sobre o telefone › toque 7× em *Número da versão* › Opções do desenvolvedor › **Depuração USB**.
2. Conecte o cabo, aceite a chave RSA no celular.
3. `adb devices` (deve listar o aparelho) e `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.

### Permissões (o app mostra o que falta na tela principal)

- **Notificações** e **Notificações em tela cheia** (Android 14+): sem elas a tela do alarme não abre sozinha.
- **Rede local** (Android 17): permissão nova; sem ela o celular não alcança o PC.
- **Alarmes e lembretes:** concedido automaticamente via `USE_EXACT_ALARM`.

### Desativar a otimização de bateria

Obrigatório para o alarme não ser adiado/morto pelo fabricante. Em todos: Configurações › Apps › Despertador › Bateria › **Sem restrições**. Além disso:

- **Samsung:** Cuidado do dispositivo › Bateria › Limites de uso em segundo plano › tire o Despertador de “Apps em suspensão”/“Apps em suspensão profunda” e adicione-o a “Apps que nunca dormem”. Em Apps › Despertador › Bateria escolha *Sem restrições*.
- **Xiaomi (MIUI/HyperOS):** Configurações › Apps › Gerenciar apps › Despertador › **Início automático** ligado › Economia de bateria › *Sem restrições*; em Recentes, toque no cadeado do app para travá-lo.
- **Motorola:** Configurações › Bateria › Otimização de bateria › Todos os apps › Despertador › *Não otimizar*; em Apps › Despertador › Bateria › *Sem restrições*.

## 3. Primeiro teste, passo a passo

1. No PC: `python despertador_pc.py --debug`; libere a porta 8765 (acima) e anote o IP exibido.
2. No celular: abra o app, conceda as permissões e resolva as pendências listadas.
3. Toque em **Parear com o computador** e escaneie o QR da janela do PC. O status passa a mostrar “pareado (ip:8765)”.
4. Toque em **Testar em 10 s** e **bloqueie a tela**. Em 10 s o celular deve tocar e acender a tela; o PC abre o QR em tela cheia.
5. No celular: **Escanear QR no computador** (ele pede o desbloqueio, depois abre o leitor). Aproxime o celular do QR; aparece o código de 6 dígitos.
6. Digite o código no PC e tecle Enter. O celular para.
7. Espere 5 min: o celular toca baixo. Repita o ritual em até 2 min — o ciclo acaba. (Se deixar passar o prazo, o volume vai ao máximo e vem nova verificação.)
8. Teste também o **plano B**: desligue o Wi-Fi do PC (ou feche o programa), faça o alarme tocar e aguarde ~60 s; aparece “Plano B: resolver contas” (8 contas seguidas; errar zera).
9. Por fim, **Definir horário** para o alarme diário e confira o ícone de alarme na barra de status.

## Limitações conhecidas

- Se o PC estiver **suspenso, desligado ou bloqueado**, o QR não aparece: valem os 60 s e o plano B (contas).
- O pedido é HTTP puro assinado: protege contra falsificação e replay fora da janela de 300 s, mas não é criptografado. Use só em Wi-Fi de confiança. O QR de pareamento contém a chave; não o mostre em chamadas/capturas de tela.
- O app não restaura o volume do alarme anterior ao tocar.
