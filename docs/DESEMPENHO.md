# Desempenho

Este documento descreve como o GearScore mantém o próprio custo baixo e o que
foi efetivamente medido.

> **O que foi e o que não foi testado**
>
> | Ambiente | Situação |
> |---|---|
> | Servidor Paper real, com jogadores reais ou bots | **não executado** |
> | MariaDB real | **executado na versão 1.1.0**: MariaDB 10.4.32 local, toda a suíte de persistência, planos e tempos das consultas, e o driver embutido no JAR. A camada de banco não mudou na 2.0.0 e esses testes **não foram repetidos** nela; a suíte de persistência da 2.0.0 rodou em H2 |
> | TAB do proxy via TAB-Bridge | **não executado** (sem acesso à rede) |
> | Simulação em JVM, sem Paper | **executado**: fila de avaliações, cálculo, cache, atualização dos servidores de consulta |
>
> Os números de simulação medem o código do plugin isoladamente. Eles **não**
> incluem o custo de ler o inventário pela API do servidor, o despacho de
> eventos do Bukkit nem qualquer efeito sobre TPS, e não permitem afirmar
> impacto zero. Os números de MariaDB vêm de uma instância local na máquina de
> desenvolvimento, não do banco de produção. A seção
> [Como medir no servidor](#como-medir-no-servidor) explica como obter os
> números reais do Serpens.

## Como o custo é contido

### O que nunca acontece

* Nenhuma tarefa percorre inventários, em nenhum servidor.
* Nenhum evento de movimentação é usado.
* Nenhuma consulta SQL, tarefa, leitura de inventário, recálculo ou espera é
  feita para responder `/gs` de jogador online ou **qualquer** placeholder,
  inclusive o de posição.
* Nenhuma thread ou tarefa é criada por jogador ou por evento.
* Nenhuma configuração do Paper é alterada; nenhuma entidade é removida.

### Detecção de mudança (servidores oficiais)

1. **Eventos.** `PlayerInventorySlotChangeEvent` (qualquer slot do inventário
   do jogador: cobre cliques, shift-click, arrastes, coleta, descarte, quebra,
   troca de mãos, morte e alterações feitas por outros plugins),
   `PlayerArmorChangeEvent`, a tecla de troca da mão secundária em contêineres,
   morte, renascimento e troca de mundo.
2. **Filtro no listener (custo constante).** O evento é descartado se nenhum
   dos dois itens é um equipamento configurado, ou se material e encantamentos
   são iguais — ou seja, mudou só a quantidade ou a durabilidade. Golpes em
   combate, blocos colocados, comida e recursos coletados param aqui.
3. **Agrupamento.** O jogador é marcado para avaliação daqui a
   `evaluation.debounce-ticks`. Novos eventos nesse intervalo não criam outra
   avaliação. Cada agendamento tem uma identificação própria: uma entrada
   cancelada (saída do jogador, avaliação imediata) nunca executa nem consome
   um pedido feito depois.
4. **Limite por tick.** Uma única tarefa processa a fila em ordem de
   vencimento, no máximo `evaluation.max-per-tick` jogadores por tick. Ela é
   cancelada quando a fila esvazia.
5. **Snapshot mínimo.** Na thread principal são lidos os 41 slots do jogador
   (36 do inventário, 4 de armadura e a mão secundária), cada um uma vez. Para
   a maioria dos slots o custo é ler o material e consultar uma tabela; só os
   itens que são equipamentos configurados têm os encantamentos reais lidos e,
   se houver regras de itens personalizados, as chaves PDC configuradas. Nome,
   lore, NBT completo, durabilidade e quantidade não são lidos.
6. **Assinatura.** O snapshot guarda os equipamentos em ordem canônica, sem o
   slot. Ele é comparado com o último avaliado: se a composição é a mesma —
   inclusive quando itens só mudaram de lugar, foram equipados ou desequipados
   — nada é recalculado nem gravado.
7. **Cálculo.** Feito na hora, sobre o snapshot, com regras pré-processadas no
   carregamento da configuração.
8. **Gravação.** Só quando o resultado difere do que já está persistido.

### Servidores de consulta

Não leem inventários. Uma tarefa por segundo, existente apenas enquanto há
jogadores online, verifica quais leituras venceram e as envia agrupadas ao
banco (entrada, reverificação e intervalo). O trabalho na thread principal é
consultar uma fila ordenada; a leitura roda nas threads do banco e o resultado
é aplicado ao cache sem voltar à thread principal.

### Posições do ranking

O placeholder só lê um mapa em memória. Um ciclo separado, a cada
`ranking.position-refresh-seconds`, faz uma leitura agrupada para os jogadores
online no servidor — e apenas se o placeholder foi pedido desde o ciclo
anterior. A frequência com que o TAB ou os hologramas pedem o valor não altera
a frequência das consultas.

### Banco

* Gravações consolidadas por UUID e enviadas em lote por uma única thread.
* Fila de gravação com limite real, inclusive quando um lote que falhou volta.
* Leituras em um pool pequeno com fila limitada; excesso é recusado.
* Ranking limitado a `ranking.max-entries` linhas, lidas em ordem de índice,
  com cache compartilhado.
* Consultas de offline com cache temporário, inclusive de "não encontrado".
* Recarregar as regras reavalia só os jogadores online, respeitando o limite
  por tick. Jogadores offline não são varridos.

## Custos acrescentados na versão 2.0.0

| Mudança | Custo |
|---|---|
| Inventário completo | a leitura passa de 14 para 41 slots por avaliação; o listener passa a considerar mudanças em qualquer slot, mas só as que trocam um equipamento configurado chegam à fila |
| Ordem canônica do snapshot | uma ordenação de, no máximo, 41 itens por leitura |
| Atualização automática dos arquivos | uma vez, na primeira inicialização após a troca do JAR |
| Mensagens e sidebar | nenhum: o plugin continua sem enviar nada quando a pontuação muda, e os placeholders só leem memória |

O tempo real da leitura de 41 slots pela API do Paper **não foi medido**; o
`/gs diag` mostra a média e o máximo no servidor.

## Custos acrescentados na versão 1.1.0

| Mudança | Custo |
|---|---|
| Atualização nos servidores de consulta | 1 tarefa por segundo na thread principal enquanto há jogadores (medida abaixo: 5–14 µs por execução na simulação); por jogador, 2 leituras nos primeiros 5 s e depois 1 a cada 30 s, sempre agrupadas |
| Posições por ciclo próprio | em servidores com PlaceholderAPI, a mesma tarefa de 1 s verifica o intervalo; no máximo 1 leitura agrupada a cada 60 s por servidor, só com o placeholder em uso (medida: ~43–59 ms de banco com 200 mil linhas) |
| Fila de avaliações com identificação e ordem por vencimento | inserção e retirada passam de O(1) para O(log n) sobre os jogadores com avaliação pendente |
| Coluna gerada `rank_score` e índice `idx_rank` | o índice substitui o anterior (`idx_score`); uma entrada de índice por linha, como antes, agora com o UUID explícito |
| Leitura do ranking | deixa de ordenar a tabela inteira em MariaDB anterior ao 10.8 e passa a ler só as linhas pedidas |

Antes, `%gearscore_position%` podia disparar uma consulta por jogador a cada
120 s a partir da chamada do placeholder; agora nenhuma consulta parte dele.

## Medições em MariaDB real

MariaDB **10.4.32** local, tabela com **200 000 linhas** e cerca de 1500
pontuações distintas, uma conexão, mediana de 5 execuções.

| Consulta | Tempo | Plano |
|---|---:|---|
| Top 100 do ranking | 0,72 ms | índice `idx_rank`, 100 linhas, sem filesort |
| 100 jogadores por UUID (atualização de servidor de consulta) | 0,80 ms | chave primária |
| 1 jogador por UUID (entrada em servidor oficial) | 0,21 ms | chave primária |
| Posições de 200 jogadores | 43–59 ms | uma passada de índice + um trecho curto por jogador |

Também executado nessa instância:

* a suíte completa de persistência (os mesmos testes que rodam em H2):
  migrações, gravação em lote, reinício, queda e recuperação, limites das
  filas, ranking com empates, posições, atualização entre servidores;
* a atualização do esquema da 1.0.0 para a 1.1.0 com dados existentes;
* o JAR final, fora do Paper, usando o próprio pool e o driver embutido:
  conectou, aplicou as migrações, gravou, leu de volta e calculou a posição
  (`MariaDB Connector/J 3.5.1 -> 10.4.32-MariaDB`).

Detalhes dos planos em [BANCO.md](BANCO.md).

## Teste de carga simulado

`./mvnw test -Pbench` (classe `EngineBenchmark`). **Não usa Paper nem banco.**

**O que é real na simulação:** a fila de agrupamento, a comparação de
assinatura, o cálculo com a configuração padrão, o cache de resultados, a
detecção de mudança de pontuação e o agendamento dos servidores de consulta.

**O que é simulado:** os jogadores, os eventos e os inventários (objetos já
montados; a cada leitura é feita uma cópia nova para reproduzir as alocações de
uma captura). A coluna de SQL aplica a política da fila de gravação (estado
mais recente por jogador, envio a cada 3 s, 100 linhas por lote) sem executar
SQL.

**Ambiente:** JDK 21.0.12, Windows 11, 16 threads lógicas. 1200 ticks medidos
(60 s de jogo) após aquecimento, `debounce-ticks: 10`, `max-per-tick: 20`.
O inventário principal de cada jogador tem 14 equipamentos válidos (armadura de
netherita encantada, seis armas e uma armadura reserva); os outros dois
conjuntos têm 7 e 5.

Cenários:

* **Combate:** 4 eventos por segundo por jogador que não mudam o equipamento
  (pior caso: como se o filtro do listener não existisse) e 5% dos jogadores
  trocando de arma a cada segundo.
* **Troca rápida:** todo jogador troca o conjunto inteiro a cada 2 ticks,
  escolhendo ao acaso entre três conjuntos.
* **Movimentação de itens:** 10 eventos por segundo por jogador; em 20% deles
  os equipamentos mudam de slot (a composição não muda).

| Cenário | Jogadores | Eventos | Agrupados | Leituras | Sem mudança | Recalculados | Média µs/tick | p99 µs/tick | Máx. µs/tick | Mudanças de pontuação | Lotes SQL | Linhas gravadas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Combate | 50 | 12 149 | 8 149 | 3 970 | 3 843 | 127 | 14,6 | 151,7 | 1 905,4 | 127 | 20 | 123 |
| Combate | 100 | 24 298 | 16 298 | 7 920 | 7 664 | 256 | 11,8 | 67,6 | 1 689,1 | 256 | 20 | 249 |
| Combate | 200 | 48 582 | 32 582 | 15 820 | 15 320 | 500 | 13,6 | 65,2 | 155,4 | 500 | 21 | 483 |
| Troca rápida | 50 | 30 000 | 25 000 | 4 970 | 1 653 | 3 317 | 22,4 | 164,9 | 312,0 | 3 317 | 20 | 995 |
| Troca rápida | 100 | 60 000 | 50 000 | 9 920 | 3 272 | 6 648 | 24,5 | 111,8 | 336,7 | 6 648 | 20 | 1 991 |
| Troca rápida | 200 | 120 000 | 100 000 | 19 820 | 6 431 | 13 389 | 31,0 | 77,0 | 101,7 | 13 389 | 40 | 3 985 |
| Movimentação | 50 | 30 000 | 25 000 | 4 970 | 4 920 | 50 | 6,4 | 41,4 | 159,2 | 50 | 1 | 50 |
| Movimentação | 100 | 60 000 | 50 000 | 9 920 | 9 820 | 100 | 25,0 | 147,2 | 314,4 | 100 | 1 | 100 |
| Movimentação | 200 | 120 000 | 100 000 | 19 820 | 19 620 | 200 | 25,7 | 126,5 | 835,9 | 200 | 2 | 200 |

Leitura dos resultados, dentro dos limites da simulação:

* Um tick tem 50 000 µs. Nos cenários medidos, a média ficou entre 6 e 31 µs
  por tick e o p99 abaixo de 170 µs.
* Os valores máximos isolados (até 1,9 ms) apareceram também em cenários leves
  e são compatíveis com pausas da própria JVM (GC/JIT) durante a medição; a
  simulação não os separa do código do plugin.
* O agrupamento absorveu de 67% a 83% dos eventos antes de qualquer leitura.
* Em "Movimentação", 120 000 eventos de 200 jogadores resultaram em 200
  recálculos (o inicial de cada jogador) e 200 linhas gravadas em 2 lotes:
  mudar equipamentos de lugar não gera recálculo nem gravação.
* Em "Troca rápida", 13 389 mudanças de pontuação viraram 3 985 linhas em 40
  lotes ao longo de 60 s, por causa da consolidação por jogador.
* As medições variam entre execuções (na versão 1.1.0, com 8 a 9 itens por
  jogador, a média ficou entre 9 e 61 µs); trate-as como ordem de grandeza.

### Servidor de consulta simulado

Jogadores entram ao longo do primeiro minuto e permanecem por dez minutos; a
tarefa roda uma vez por segundo (600 execuções). O "banco" é um mapa em
memória: o que se mede é o trabalho de agendamento na thread principal e
quantas leituras agrupadas são pedidas.

| Jogadores | Leituras agrupadas em 10 min | Jogadores por leitura | Média µs por execução | Máx. µs |
|---:|---:|---:|---:|---:|
| 50 | 81 | 12,6 | 11,4 | 3 006,7 |
| 100 | 82 | 24,8 | 5,1 | 193,1 |
| 200 | 100 | 40,7 | 8,0 | 248,9 |

A maior parte das leituras acontece durante o minuto de entradas (leitura de
entrada e reverificação de cada jogador). Depois disso, 200 jogadores custam 2
leituras a cada 30 s. O máximo de 3,0 ms ocorreu na primeira execução da
primeira rodada, antes do aquecimento da JVM.

Combinando com a medição em MariaDB real (0,80 ms para 100 jogadores por
UUID), a atualização de 200 jogadores em um servidor de consulta representa da
ordem de 2 ms de banco a cada 30 s.

### Tarefas e threads

| Recurso | Quantidade |
|---|---|
| Tarefa de avaliação (servidor oficial) | 1, a cada tick, existente apenas enquanto há avaliação pendente |
| Tarefa de atualização (servidor de consulta, ou qualquer servidor com PlaceholderAPI) | 1, a cada segundo, existente apenas enquanto há jogadores online |
| Tarefas por evento ou por chamada de placeholder | 0 |
| Tarefas síncronas por entrada de jogador | servidor oficial: 1, quando o jogador já tem linha no banco; servidor de consulta: 0 |
| Threads do plugin | 1 de gravação + `reads.threads` de leitura (padrão 1), além das threads internas do pool de conexões |

### Consultas SQL

| Operação | Quantidade |
|---|---|
| Entrada em servidor oficial | 1 `SELECT` por chave primária |
| Gravação | no máximo 1 lote a cada `flush-interval-ms` enquanto houver mudanças; 1 linha por jogador alterado |
| Jogador em servidor de consulta | leituras agrupadas: na entrada, 5 s depois e a cada 30 s |
| `/gs` e `/gs detalhes` de jogador online; todos os placeholders | 0 |
| `/gs top` | no máximo 1 `SELECT ... LIMIT` por `ranking.cache-seconds`, qualquer que seja o número de jogadores abrindo |
| `/gs ver` de offline | 1 `SELECT` por nome a cada `offline-cache-seconds` |
| Posições | no máximo 1 leitura agrupada por servidor a cada `position-refresh-seconds`, só com o placeholder em uso |

Verificado nos testes automatizados (H2; na 1.1.0, também MariaDB 10.4.32):

* 50 alterações do mesmo jogador resultaram em 1 linha e 1 lote; 250 jogadores,
  em 3 lotes; 20 aberturas seguidas do ranking executaram 1 consulta;
* 100 000 leituras seguidas do placeholder de posição não abriram conexão, não
  executaram consulta e não deixaram trabalho na fila; chamadas repetidas do
  ciclo de posições dentro do intervalo não consultaram; sem pedidos do
  placeholder, o ciclo não consulta;
* 450 jogadores entrando ao mesmo tempo em um servidor de consulta custaram 5
  leituras agrupadas, 3 por ciclo;
* a pontuação lida na hub antes da gravação do New Era foi corrigida na
  reverificação, e uma alteração posterior chegou no intervalo.

### Memória

Medição aproximada por diferença de heap após GC, com 2000 jogadores
simulados com 14 equipamentos cada: **cerca de 4 KB por jogador acompanhado**
(estado, último snapshot e resultado com detalhamento). O valor cresce com a
quantidade de equipamentos válidos no inventário, até o limite de 41. Para 200
jogadores isso representa cerca de 1 MB. Os dados são removidos
quando o jogador sai.

Em servidores de consulta o custo por jogador é menor: um registro com a
pontuação oficial e uma entrada na agenda de leituras.

Limites fixos, independentes do número de jogadores: `writes.max-pending`
gravações pendentes (padrão 5000), `reads.offline-cache-size` consultas de
offline (padrão 500), `reads.max-queued` leituras na fila (padrão 64) e
`ranking.max-entries` linhas de ranking (padrão 100).

### Banco indisponível

Simulação com 200 jogadores em "Troca rápida" e o banco (H2) recusando todas as
conexões:

| Medida | Resultado |
|---|---|
| Tempo na thread principal | média 53,1 µs/tick, máximo 4 017,3 µs |
| Mudanças de pontuação enviadas à fila | 13 389 |
| Pendentes na memória ao final | 200 (uma por jogador) |
| Consolidadas | 13 189 |
| Descartadas | 0 |
| Tentativas de conexão que falharam | 1 em 407 ms de execução |
| Desligamento com o banco ainda fora | retornou em 2 ms, 200 atualizações não gravadas |

A thread principal nunca espera o banco: ela apenas coloca o registro na fila.

Verificado nos testes automatizados (H2; na 1.1.0, também MariaDB 10.4.32;
a queda é simulada na obtenção da conexão):

* durante a queda, as atualizações ficam pendentes e uma mais nova substitui a
  anterior do mesmo jogador;
* as tentativas são espaçadas (no máximo 4 em 300 ms com espera inicial de
  40 ms) e nenhuma conexão é pedida durante a janela de espera;
* quando o banco volta, tudo é gravado e o estado final é o mais recente;
* com `max-pending: 100` e 500 jogadores alterados durante a queda, a memória
  fica em 100 pendentes e 400 são descartadas;
* um lote que falha no meio do envio volta para a fila sem ultrapassar o
  limite e sem substituir um estado mais novo do mesmo jogador;
* em servidor de consulta, uma leitura que falha na entrada é refeita e o
  jogador recebe a pontuação depois que o banco volta; enquanto o banco está
  na janela de espera, nenhuma leitura é enviada;
* o desligamento com o banco fora respeita o prazo.

## Como medir no servidor

Para obter números reais do Serpens:

1. **`/gs diag`** mostra, desde o início do servidor:
   * eventos filtrados no listener;
   * pedidos de avaliação e quantos foram agrupados;
   * leituras de equipamento, quantas foram descartadas sem mudança e quantas
     recalculadas;
   * tempo médio e máximo de leitura + cálculo, em µs, **já incluindo a leitura
     real do inventário**;
   * em servidores de consulta: jogadores em cache, consultas agrupadas
     enviadas, respostas aplicadas e descartadas por atraso;
   * acertos e faltas de cache;
   * gravações pendentes, consolidadas, descartadas, lotes e linhas;
   * leituras SQL, fila e recusas; estado do banco e total de falhas.
   Nenhuma credencial é exibida.
2. **`/spark profiler`** durante um evento de PvP: procure
   `br.com.serpens.gearscore` na thread principal.
3. **Banco:** `SHOW PROCESSLIST`, o log de consultas lentas do MariaDB e
   `EXPLAIN` das consultas listadas em [BANCO.md](BANCO.md).
4. **Queda de banco:** pare o MariaDB por um minuto em horário de teste e
   acompanhe o `/gs diag` e o console.
5. **Suíte contra o banco real:** `./mvnw test -Pmariadb` com um banco de
   testes (veja [INSTALACAO.md](INSTALACAO.md)) repete os planos e tempos deste
   documento no seu servidor.

Se o tempo médio por leitura + cálculo ficar alto, aumente
`evaluation.debounce-ticks` ou reduza `evaluation.max-per-tick`. Se a leitura
de posições pesar no banco, aumente `ranking.position-refresh-seconds`.
