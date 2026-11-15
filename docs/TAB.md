# GearScore na sidebar do TAB

Mostra uma linha `⚔ GearScore: 1125` na sidebar que o TAB já exibe. Não cria
outra scoreboard e não exige Redis nem qualquer serviço adicional.

> **Situação da validação**
>
> * **Testes automatizados (executados):** os placeholders respondem só com
>   dados em memória, devolvem `—` enquanto não há valor, acompanham o cache
>   quando um novo cálculo entra e não geram consultas nem tarefas, qualquer que
>   seja a frequência das chamadas; a pontuação lida na hub antes da gravação do
>   New Era é corrigida na reverificação.
> * **Na rede Serpens (não executado):** a exibição pelo TAB do proxy através
>   do TAB-Bridge **não foi validada**, porque não há acesso ao proxy, aos
>   backends nem à configuração atual do TAB. As instruções abaixo seguem a
>   documentação oficial do TAB; os passos de verificação da última seção são o
>   teste a fazer na rede.

## Como funciona

```
New Era: inventário muda → debounce (0,5 s) → cálculo → cache do GearScore
                                                          │
PlaceholderAPI (backend) ← TAB-Bridge (backend) lê %gearscore_score% no intervalo configurado
                                                          │
                                   TAB (Velocity) atualiza a linha da sidebar
```

* O TAB fica no proxy e não fala com o PlaceholderAPI. Quem resolve
  `%gearscore_score%` é o **TAB-Bridge de cada backend**, usando o
  PlaceholderAPI daquele backend, e envia o valor ao proxy.
* Por isso o **GearScore e o PlaceholderAPI precisam estar em todo backend**
  onde a linha deve aparecer (New Era, hub, eventos). Registrar a expansão só
  no New Era não basta: na hub quem responde é a expansão da hub.
* Na hub e nos eventos o GearScore não lê inventários. Ele mostra a pontuação
  oficial gravada pelo New Era, mantida em cache por leituras controladas do
  MariaDB: na entrada, 5 s depois e a cada 30 s. Kits locais não alteram o
  valor.
* A leitura do placeholder é só memória: sem SQL, sem leitura de inventário,
  sem recálculo e sem criar tarefas. Aumentar a frequência do TAB não aumenta
  o trabalho do banco.

## Placeholders

| Placeholder | Valor |
|---|---|
| `%gearscore_score%` | pontuação total (`%gearscore_total%` é equivalente) |
| `%gearscore_tier%` | classificação, sem cores |
| `%gearscore_tier_colored%` | classificação com cores |

Enquanto o dado não está disponível, todos devolvem `—`
(`placeholders.fallback` no `config.yml` do GearScore).

## O que instalar e onde

| Servidor | Plugin | Observação |
|---|---|---|
| Velocity | TAB | já instalado |
| New Era, hub, eventos | TAB-Bridge | já instalado; não tem arquivo de configuração |
| New Era, hub, eventos | PlaceholderAPI | já instalado |
| New Era, hub, eventos | GearScore 2.0.0 | a expansão do PlaceholderAPI é registrada sozinha |

Nada é instalado no proxy além do TAB que já existe. O GearScore não depende
do TAB: sem ele, o plugin funciona normalmente.

## Alterações na configuração do TAB

Todas no **proxy Velocity**, arquivo **`plugins/tab/config.yml`**. Insira
apenas os trechos abaixo; não substitua o arquivo.

### 1. Linha na sidebar

Dentro da scoreboard que já está em uso, em
`scoreboard.scoreboards.<nome-da-sua-scoreboard>.lines`, acrescente **uma
linha** na posição desejada, sem alterar título, logo, tags ou as outras
linhas:

```yaml
        - "&6⚔ &fGearScore: &e%gearscore_score%"
```

Para mostrar também a classificação, use esta variação (ou uma segunda linha):

```yaml
        - "&6⚔ &fGearScore: &e%gearscore_score% &7(%gearscore_tier%&7)"
```

A sidebar do cliente mostra no máximo 15 linhas; se a sua já tem 15, uma
precisa sair.

### 2. Intervalo de atualização

Na seção `placeholder-refresh-intervals` que já existe, acrescente:

```yaml
placeholder-refresh-intervals:
  "%gearscore_score%": 1000
  "%gearscore_tier%": 1000
```

O valor é em milissegundos e precisa ser divisível por 50. Sem essas linhas o
TAB usa `default-refresh-interval`. Um segundo é suficiente: a pontuação só
muda depois do debounce do GearScore.

### 3. Texto enquanto o Bridge ainda não respondeu

Logo após a troca de servidor, antes de o TAB-Bridge enviar o primeiro valor, o
TAB do proxy pode mostrar o nome cru do placeholder. Para exibir `—` nesse
instante, acrescente em `placeholder-output-replacements`:

```yaml
placeholder-output-replacements:
  "%gearscore_score%":
    "%gearscore_score%": "—"
  "%gearscore_tier%":
    "%gearscore_tier%": "—"
```

Depois de editar, recarregue o TAB no proxy com o comando de reload do próprio
TAB.

## Quanto tempo a linha leva para atualizar

| Situação | Atraso esperado |
|---|---|
| Troca de equipamento no New Era | debounce do GearScore (`evaluation.debounce-ticks`, 0,5 s) + intervalo do TAB (1 s) |
| Chegada à hub ou aos eventos | primeira leitura do banco em até 1 s; se o New Era ainda não tinha gravado, a correção vem na reverificação (`query-refresh.join-recheck-seconds`, 5 s) |
| Mudança feita por outro meio enquanto o jogador está na hub | até `query-refresh.interval-seconds` (30 s) |

## Verificação na rede

Faça nesta ordem; cada passo isola uma parte do caminho.

1. **Expansão no backend.** No New Era, dentro do jogo:
   `/papi parse me %gearscore_score%`. Deve devolver um número. Se devolver o
   próprio texto `%gearscore_score%`, o GearScore ou o PlaceholderAPI não
   carregou naquele servidor. Repita na hub e nos eventos.
2. **Ponte.** Use o comando `/btab dump <jogador>` descrito na página TAB-Bridge
   da wiki do TAB e confira que a conexão com o
   proxy aparece como conectada. TAB e TAB-Bridge devem estar na mesma versão
   recente.
3. **Sidebar no New Era.** A linha mostra o mesmo número do `/gs`.
4. **Atualização.** Tire uma peça de armadura do inventário (jogue no chão): em
   cerca de 2 s a linha muda, sem nenhuma mensagem no chat. Pegue de volta: o
   valor retorna.
5. **Transferência.** Vá para a hub: a linha mostra a pontuação do New Era
   (em até ~5 s, o valor final). Vista um kit da hub: o número **não** muda.
   Repita nos eventos.
6. **Retorno.** Volte ao New Era: o valor é o do inventário real.
7. **Custo.** `/gs diag` na hub mostra as consultas agrupadas; o comando de CPU
   do TAB no proxy mostra o uso dos placeholders.

Se a linha ficar em `—` na hub: o jogador nunca foi avaliado no New Era, ou a
hub não alcança o banco (`/gs diag` mostra o estado).
