# Desempenho

Este documento descreve como o GearScore mantém o próprio custo baixo e o que
foi efetivamente medido.

> **O que foi e o que não foi testado**
>
> * **Executado:** testes automatizados e um teste de carga **simulado**, em
>   uma JVM comum, sem servidor Paper.
> * **Não executado:** nenhum teste em servidor Paper real, com jogadores reais
>   ou bots, nem contra um MariaDB real. Os testes de banco usam H2 em modo de
>   compatibilidade MySQL.
>
> Os números abaixo medem o código do plugin isoladamente. Eles **não** incluem
> o custo de ler o inventário pela API do servidor, o despacho de eventos do
> Bukkit nem qualquer efeito sobre TPS, e não permitem afirmar impacto zero. A
> seção [Como medir no servidor](#como-medir-no-servidor) explica como obter os
> números reais do Serpens.

## Como o custo é contido

### O que nunca acontece

* Nenhuma tarefa percorre jogadores ou inventários continuamente.
* Nenhum evento de movimentação é usado.
* Nenhuma consulta SQL, leitura de inventário ou recálculo é feito para
  responder `/gs` de jogador online ou um placeholder.
* Nenhuma thread ou tarefa assíncrona é criada por jogador ou por evento.
* Nenhuma configuração do Paper é alterada; nenhuma entidade é removida.

### Detecção de mudança

1. **Eventos.** `PlayerInventorySlotChangeEvent` (hotbar, armadura e mão
   secundária), `PlayerArmorChangeEvent`, a tecla de troca da mão secundária em
   contêineres, renascimento e troca de mundo.
2. **Filtro no listener (custo constante).** O evento é descartado se o slot
   não participa da fórmula, se nenhum dos dois itens é um equipamento
   configurado, ou se material e encantamentos são iguais — ou seja, mudou só a
   quantidade ou a durabilidade. Golpes em combate e blocos colocados param
   aqui.
3. **Agrupamento.** O jogador é marcado para avaliação daqui a
   `evaluation.debounce-ticks`. Novos eventos nesse intervalo não criam outra
   avaliação.
4. **Limite por tick.** Uma única tarefa processa a fila, no máximo
   `evaluation.max-per-tick` jogadores por tick. Ela é cancelada quando a fila
   esvazia: servidor parado, plugin parado.
5. **Snapshot mínimo.** Na thread principal são lidos 4 slots de armadura, 9
   da hotbar e a mão secundária: material, encantamentos reais e, se houver
   regras de itens personalizados, apenas as chaves PDC configuradas. Nome,
   lore, NBT completo, durabilidade e quantidade não são lidos.
6. **Assinatura.** O snapshot é comparado com o último avaliado. Igual: nada é
   recalculado.
7. **Cálculo.** Feito na hora, sobre o snapshot, com regras pré-processadas no
   carregamento da configuração (somas e buscas em tabela). É curto o bastante
   para não justificar uma tarefa assíncrona por evento.
8. **Gravação.** Só quando o resultado difere do que já está persistido.

### Banco

* Gravações consolidadas por UUID e enviadas em lote por uma única thread.
* Leituras em um pool pequeno com fila limitada; excesso é recusado.
* Ranking limitado a `ranking.max-entries` linhas, com cache compartilhado.
* Consultas de offline com cache temporário, inclusive de "não encontrado".
* Recarregar as regras reavalia só os jogadores online, respeitando o limite
  por tick. Jogadores offline não são varridos.

## Teste de carga simulado

`./mvnw test -Pbench` (classe `EngineBenchmark`).

**O que é real na simulação:** a fila de agrupamento, a comparação de
assinatura, o cálculo com a configuração padrão, o cache de resultados e a
detecção de mudança de pontuação.

**O que é simulado:** os jogadores, os eventos e os inventários (objetos já
montados; a cada leitura é feita uma cópia nova para reproduzir as alocações de
uma captura). A coluna de SQL aplica a política da fila de gravação (estado
mais recente por jogador, envio a cada 3 s, 100 linhas por lote) sem executar
SQL.

**Ambiente:** JDK 21.0.12, Windows 11, 16 threads lógicas. 1200 ticks medidos
(60 s de jogo) após aquecimento, `debounce-ticks: 10`, `max-per-tick: 20`.
Cada jogador usa armadura de netherita totalmente encantada e 4 armas na
hotbar/mão secundária.

Cenários:

* **Combate:** 4 eventos por segundo por jogador que não mudam o equipamento
  (pior caso: como se o filtro do listener não existisse) e 5% dos jogadores
  trocando de arma a cada segundo.
* **Troca rápida:** todo jogador troca o conjunto inteiro a cada 2 ticks,
  escolhendo ao acaso entre três conjuntos.
* **Movimentação de itens:** 10 eventos por segundo por jogador; em 20% deles
  a arma muda de slot na hotbar (o equipamento muda de posição, a pontuação
  não).

| Cenário | Jogadores | Eventos | Agrupados | Leituras | Sem mudança | Recalculados | Média µs/tick | p99 µs/tick | Máx. µs/tick | Mudanças de pontuação | Lotes SQL | Linhas gravadas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Combate | 50 | 12 149 | 8 149 | 3 970 | 3 843 | 127 | 7,5 | 82,3 | 1 257,6 | 127 | 20 | 123 |
| Combate | 100 | 24 298 | 16 298 | 7 920 | 7 664 | 256 | 7,6 | 43,2 | 2 038,8 | 256 | 20 | 249 |
| Combate | 200 | 48 582 | 32 582 | 15 820 | 15 320 | 500 | 17,8 | 115,4 | 472,9 | 500 | 21 | 483 |
| Troca rápida | 50 | 30 000 | 25 000 | 4 970 | 1 653 | 3 317 | 8,4 | 60,1 | 129,8 | 3 317 | 20 | 995 |
| Troca rápida | 100 | 60 000 | 50 000 | 9 920 | 3 272 | 6 648 | 15,1 | 71,4 | 172,7 | 6 648 | 20 | 1 991 |
| Troca rápida | 200 | 120 000 | 100 000 | 19 820 | 6 431 | 13 389 | 28,2 | 95,1 | 1 080,7 | 13 389 | 40 | 3 985 |
| Movimentação | 50 | 30 000 | 25 000 | 4 970 | 1 732 | 3 238 | 7,4 | 56,9 | 172,7 | 50 | 1 | 50 |
| Movimentação | 100 | 60 000 | 50 000 | 9 920 | 3 415 | 6 505 | 27,8 | 125,3 | 244,0 | 100 | 1 | 100 |
| Movimentação | 200 | 120 000 | 100 000 | 19 820 | 6 824 | 12 996 | 32,3 | 77,1 | 235,3 | 200 | 2 | 200 |

Leitura dos resultados, dentro dos limites da simulação:

* Um tick tem 50 000 µs. No pior cenário medido (200 jogadores trocando de
  conjunto a cada 2 ticks) a média foi de 28 µs por tick, com p99 de 95 µs.
* Os valores máximos isolados (1–2 ms) apareceram também em cenários leves e
  são compatíveis com pausas da própria JVM (GC/JIT) durante a medição; a
  simulação não os separa do código do plugin.
* O agrupamento absorveu de 67% a 83% dos eventos antes de qualquer leitura.
* Em "Movimentação", 120 000 eventos de 200 jogadores resultaram em 200 linhas
  gravadas em 2 lotes: mudar o item de lugar não gera gravação.
* Em "Troca rápida", 13 389 mudanças de pontuação viraram 3 985 linhas em 40
  lotes ao longo de 60 s, por causa da consolidação por jogador.
* As medições variam entre execuções; trate-as como ordem de grandeza.

### Tarefas e threads

| Recurso | Quantidade |
|---|---|
| Tarefas repetitivas na thread principal | 1, existente apenas enquanto há avaliação pendente |
| Tarefas assíncronas por evento | 0 |
| Tarefas síncronas por entrada de jogador | 1, quando o jogador já tem linha no banco |
| Threads do plugin | 1 de gravação + `reads.threads` de leitura (padrão 1), além das threads internas do pool de conexões |

### Consultas SQL

| Operação | Quantidade |
|---|---|
| Entrada de jogador | 1 `SELECT` por chave primária |
| Gravação | no máximo 1 lote a cada `flush-interval-ms` enquanto houver mudanças; 1 linha por jogador alterado |
| `/gs` e `/gs detalhes` de jogador online, placeholders de total/armadura/arma/faixa | 0 |
| `/gs top` | no máximo 1 `SELECT ... LIMIT` por `ranking.cache-seconds`, qualquer que seja o número de jogadores abrindo |
| `/gs ver` de offline | 1 `SELECT` por nome a cada `offline-cache-seconds` |
| `%gearscore_position%` | 0 para quem está no topo em cache; fora dele, no máximo 1 `COUNT` por jogador a cada `position-cache-seconds` |

Verificado nos testes automatizados (H2): 50 alterações do mesmo jogador
resultaram em 1 linha e 1 lote; 250 jogadores resultaram em 3 lotes; 20
aberturas seguidas do ranking executaram 1 consulta.

### Memória

Medição aproximada por diferença de heap após GC, com 2000 jogadores
simulados: **cerca de 2,4 KB por jogador acompanhado** (estado, último snapshot
e resultado com detalhamento). Em outra execução, com os inventários de cada
jogador em objetos separados, a mesma medição ficou em cerca de 4,9 KB. Para
200 jogadores isso representa algo entre 0,5 MB e 1 MB. Os dados são removidos
quando o jogador sai.

Limites fixos, independentes do número de jogadores: `writes.max-pending`
gravações pendentes (padrão 5000), `reads.offline-cache-size` consultas de
offline (padrão 500) e `ranking.max-entries` linhas de ranking (padrão 100).

### Banco indisponível

Simulação com 200 jogadores em "Troca rápida" e o banco (H2) recusando todas as
conexões:

| Medida | Resultado |
|---|---|
| Tempo na thread principal | média 44,9 µs/tick, máximo 1 787,5 µs |
| Mudanças de pontuação enviadas à fila | 13 389 |
| Pendentes na memória ao final | 200 (uma por jogador) |
| Consolidadas | 13 089 |
| Descartadas | 0 |
| Tentativas de conexão que falharam | 1 em 364 ms de execução |
| Desligamento com o banco ainda fora | retornou em 2 ms, 200 atualizações não gravadas |

A thread principal nunca espera o banco: ela apenas coloca o registro na fila.

Verificado nos testes automatizados (H2):

* durante a queda, as atualizações ficam pendentes e uma mais nova substitui a
  anterior do mesmo jogador;
* as tentativas são espaçadas (no máximo 4 em 300 ms com espera inicial de
  40 ms) e nenhuma conexão é aberta durante a janela de espera;
* quando o banco volta, tudo é gravado e o estado final é o mais recente;
* com `max-pending: 100` e 500 jogadores alterados durante a queda, a memória
  fica em 100 pendentes e 400 são descartadas;
* o desligamento com o banco fora respeita o prazo.

Além disso, o JAR final foi executado fora do servidor contra uma porta
fechada: o pool foi criado em 42 ms sem conexão, a primeira tentativa falhou
com erro de conexão e a segunda foi recusada imediatamente pela janela de
espera.

## Como medir no servidor

Para obter números reais do Serpens:

1. **`/gs diag`** mostra, desde o início do servidor:
   * eventos filtrados no listener;
   * pedidos de avaliação e quantos foram agrupados;
   * leituras de equipamento, quantas foram descartadas sem mudança e quantas
     recalculadas;
   * tempo médio e máximo de leitura + cálculo, em µs, **já incluindo a leitura
     real do inventário**;
   * acertos e faltas de cache;
   * gravações pendentes, consolidadas, descartadas, lotes e linhas;
   * leituras SQL, fila e recusas; estado do banco e total de falhas.
   Nenhuma credencial é exibida.
2. **`/spark profiler`** durante um evento de PvP: procure
   `br.com.serpens.gearscore` na thread principal.
3. **Banco:** `SHOW PROCESSLIST` e o log de consultas lentas do MariaDB.
4. **Queda de banco:** pare o MariaDB por um minuto em horário de teste e
   acompanhe o `/gs diag` e o console.

Se o tempo médio por leitura + cálculo ficar alto, aumente
`evaluation.debounce-ticks` ou reduza `evaluation.max-per-tick`.
