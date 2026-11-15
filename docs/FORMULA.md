# Fórmula e balanceamento

O GearScore é uma **estimativa da força do melhor conjunto que o jogador
consegue montar** com o que carrega. Ele não mede habilidade, poções, comida,
totens nem posicionamento.

## Fórmula

```
GearScore = melhor capacete + melhor peitoral + melhor calça + melhor bota + melhor arma
peça      = base (material e tipo) + encantamentos
```

Para cada um dos cinco lugares, o plugin pontua todos os candidatos que o
jogador carrega e fica com o de **maior pontuação completa**.

* A escolha é pelo total da peça (material + encantamentos + regra de item
  personalizado), nunca só pelo material ou pela quantidade de encantamentos.
  Um peitoral de diamante com Proteção IV (225) vence um de netherita sem
  encantamentos (90).
* Uma peça guardada no inventário pode vencer a que está equipada.
* A "melhor arma" disputa entre todos os tipos configurados: espada, machado,
  mace, tridente, arco, besta e lança.
* Peças não escolhidas **não somam**. Carregar dez espadas, ou uma segunda
  armadura, dá o mesmo resultado que carregar só as melhores.
* Em caso de empate entre duas peças, a escolha é sempre a mesma, qualquer que
  seja a posição dos itens.
* Um lugar sem candidato vale zero.

As quatro parcelas internas (base e encantamentos das armaduras, base e
encantamentos da arma) são somadas com casas decimais e arredondadas uma única
vez; o total é a soma delas. O `/gs` sempre fecha com o `/gs detalhes`.

### Onde o plugin procura

| Lido | Slots |
|---|---:|
| Inventário principal, hotbar incluída | 36 |
| Armadura equipada | 4 |
| Mão secundária | 1 |

Cada slot é lido uma vez. A mão principal é um slot da hotbar e não é lida de
novo. O lugar do item não importa: mover equipamentos de slot, equipar ou
desequipar não muda a pontuação nem gera gravação.

### O que não é lido

Ender Chest, baús e outros contêineres abertos, o item no cursor, a grade de
criação e o conteúdo de shulkers, bundles ou qualquer recipiente.

### O que é um equipamento válido

Somente os materiais listados em `armor` e `weapons` do `config.yml` e os itens
personalizados reconhecidos por PDC. Um item é admitido **pelo material**, antes
de qualquer leitura de encantamento.

Não pontuam nem disputam lugar, mesmo com encantamentos ou brilho: blocos, comida, recursos,
livros encantados, ferramentas (picareta, pá, enxada), escudo, vara de pescar e
qualquer outro item fora da lista. Nome, lore, CustomModelData e brilho visual
(`enchantment_glint_override`) nunca são lidos.

### Pontuação de um item

```
item = (base + bônus de item personalizado) + soma dos encantamentos
encantamento = pontos do nível × multiplicador da categoria
```

* **base**: `armor.materials.<material>.<slot>` ou `weapons.<tipo>.<material>`,
  multiplicada por `armor.slot-multipliers` / `weapons.type-multipliers`.
* Um item nunca fica abaixo de zero, mesmo com maldições negativas.

### Pontos por nível

```yaml
"minecraft:protection": { category: combat, levels: [30, 65, 105, 150] }   # tabela
"minecraft:smite":      { category: mob, per-level: 6, max-level: 5 }      # linear
```

* `levels` é uma tabela: nível 1, nível 2, ...
* `per-level` é a progressão linear. Combinado com `levels`, vale para cada
  nível além do fim da tabela (com `above-max-level: allow`).
* `max-level` é o limite. Se omitido: tamanho da tabela ou o máximo vanilla.

Todos os valores de balanceamento estão no `config.yml`; o código não contém
pesos.

### Onde cada encantamento pontua

Um encantamento só pontua se houver regra para ele **no tipo da peça em que
está**. A regra é procurada em `enchantments.rules`, da mais específica para a
mais geral:

1. o tipo da peça (`helmet`, `chestplate`, `leggings`, `boots`, `sword`, `axe`,
   `mace`, `trident`, `bow`, `crossbow`, `spear`)
2. a família (`armor` ou `weapon`)
3. `any`

Cada encantamento fica no grupo das peças que realmente podem recebê-lo.
`any` contém apenas o que todo equipamento aceita (Inquebrável, Remendo e
Maldição do Desaparecimento). Assim, Peso-Pena forçado em um capacete, Afiação
em uma mace ou Proteção em uma espada valem **zero**: não existe regra genérica
que distribua pontos onde o encantamento não se aplica.

Encantamentos sem regra (inclusive os de datapacks e de outros plugins) valem
zero até serem configurados. O `/gs diag` lista as chaves encontradas sem regra
e o `/gs detalhes` marca o encantamento.

As regras são lidas do arquivo exatamente como está escrito: apagar uma linha
faz o encantamento valer zero, sem que o padrão do plugin volte a preenchê-la.

### Níveis acima do limite

`enchantments.above-max-level`:

| Valor | Afiação X (limite V = 155 pontos) |
|---|---|
| `cap` (padrão) | 155 — conta como o nível máximo |
| `zero` | 0 — o encantamento não pontua |
| `allow` | 155 + `per-level` × 5 níveis extras |

### Combinações incompatíveis

`enchantments.incompatible.groups` lista os encantamentos que o vanilla não
permite juntos. `mode` define o que fazer quando um item tem mais de um do
mesmo grupo:

| Valor | Peitoral com Proteção IV (150) + Proteção contra Explosões IV (75) |
|---|---|
| `highest` (padrão) | 150 — só o de maior pontuação conta |
| `sum` | 225 — todos contam |
| `zero` | 0 — nenhum do grupo conta |

### Maldições

Valor negativo (penaliza), `0` (neutro) ou positivo. Padrão: −10 cada.

### Itens personalizados

Opcional (`custom-items.enabled`). Um item só é tratado como especial se tiver a
chave configurada no **PersistentDataContainer**; somente as chaves listadas são
consultadas.

```
item = (base ou base substituída + bonus + encantamentos) × multiplier
```

## Pesos finais (configuração padrão)

A prioridade é a força em combate contra jogadores.

### Armaduras

| Encantamento | Onde | Pontos por nível | Categoria | Por quê |
|---|---|---|---|---|
| Proteção | qualquer armadura | 30 / 65 / 105 / 150 | combat | reduz todo tipo de dano; o encantamento mais importante da peça |
| Proteção contra Explosões | qualquer armadura | 15 / 32 / 52 / 75 | conditional | forte contra cristais e âncoras, inútil contra espada; metade da geral |
| Proteção contra Projéteis | qualquer armadura | 15 / 32 / 52 / 75 | conditional | só contra arco, besta e tridente |
| Proteção contra Fogo | qualquer armadura | 12 / 26 / 42 / 60 | conditional | só contra fogo e lava |
| Espinhos | qualquer armadura | 15 / 30 / 45 | conditional | devolve dano, mas depende de o adversário bater corpo a corpo e gasta a peça |
| Peso-Pena | botas | 10 / 20 / 30 / 40 | mobility | reduz dano de queda (pérolas, mace, repulsão em altura) |
| Passos Profundos | botas | 6 / 12 / 18 | mobility | só na água |
| Passo Furtivo | calças | 6 / 12 / 18 | mobility | só agachado |
| Velocidade das Almas | botas | 5 / 10 / 15 | mobility | só em areia e terra das almas |
| Andarilho do Gelo | botas | 4 / 8 | mobility | situacional |
| Respiração | capacete | 4 / 8 / 12 | utility | só debaixo d'água |
| Afinidade Aquática | capacete | 5 | utility | coleta debaixo d'água |
| Maldição da Ligação | qualquer armadura | −10 | curse | |

### Armas

| Encantamento | Onde | Pontos por nível | Categoria | Por quê |
|---|---|---|---|---|
| Afiação | espada, machado, lança | 25 / 50 / 80 / 115 / 155 | combat | dano contra qualquer alvo |
| Densidade | mace | 30 / 60 / 95 / 130 / 170 | combat | multiplica o dano do golpe em queda, a função da mace |
| Ruptura | mace | 30 / 60 / 90 / 120 | combat | ignora parte da armadura |
| Rajada de Vento | mace | 20 / 40 / 60 | mobility | reposiciona para novo golpe; não causa dano por si |
| Aspecto Flamejante | espada, mace, lança | 25 / 45 | combat | dano contínuo e pressão |
| Repulsão | espada, lança | 12 / 22 | conditional | controle de distância, sem dano |
| Alcance | espada | 8 / 16 / 24 | conditional | só contra vários alvos |
| Investida | lança | 15 / 30 / 45 | mobility | aproximação |
| Julgamento | espada, machado, mace, lança | 6 por nível | mob | só mortos-vivos |
| Ruína dos Artrópodes | espada, machado, mace, lança | 4 por nível | mob | só artrópodes |
| Saque | espada, lança | 2 por nível | utility | coleta |
| Eficiência | machado | 1 por nível | utility | coleta |
| Toque Suave, Fortuna | machado | 0 | utility | coleta; reconhecidos, sem pontos |
| Força | arco | 20 / 40 / 65 / 90 / 120 | combat | dano da flecha |
| Chama | arco | 25 | combat | fogo no alvo |
| Impacto | arco | 10 / 20 | conditional | empurra, sem dano |
| Infinidade | arco | 15 | utility | economia de flechas |
| Carga Rápida | besta | 20 / 40 / 60 | combat | cadência |
| Perfuração | besta | 8 / 16 / 24 / 32 | conditional | atravessa alvos e escudos |
| Tiro Múltiplo | besta | 25 | conditional | área |
| Lealdade | tridente | 15 / 25 / 35 | combat | arma de arremesso reutilizável |
| Correnteza | tridente | 20 / 35 / 50 | mobility | só na água ou na chuva |
| Canalização | tridente | 10 | conditional | só em tempestade |
| Empalamento | tridente | 5 por nível | mob | no Java, só mobs aquáticos |

### Qualquer equipamento

| Encantamento | Pontos | Categoria | Por quê |
|---|---|---|---|
| Inquebrável | 5 / 10 / 15 | utility | durabilidade não decide uma luta |
| Remendo | 12 | utility | idem |
| Maldição do Desaparecimento | −10 | curse | |

`enchantments.category-multipliers` sobe ou desce uma categoria inteira (por
exemplo `utility: 0.0` zera Inquebrável e Remendo).

### Bases

| Armadura | Capacete | Peitoral | Calças | Botas | Conjunto |
|---|---:|---:|---:|---:|---:|
| Couro | 12 | 21 | 17 | 10 | 60 |
| Ouro | 18 | 32 | 26 | 14 | 90 |
| Cobre | 19 | 33 | 28 | 15 | 95 |
| Malha | 22 | 38 | 32 | 18 | 110 |
| Ferro | 28 | 48 | 42 | 22 | 140 |
| Diamante | 45 | 75 | 65 | 35 | 220 |
| Netherita | 55 | 90 | 75 | 45 | 265 |

Casco de tartaruga 25 · Élitros 0

| Arma | Madeira | Ouro | Pedra | Cobre | Ferro | Diamante | Netherita |
|---|---:|---:|---:|---:|---:|---:|---:|
| Espada | 20 | 22 | 30 | 35 | 45 | 65 | 80 |
| Machado | 22 | 22 | 32 | 36 | 46 | 62 | 76 |
| Lança | 15 | 18 | 22 | 26 | 34 | 48 | 60 |

Mace 110 · Tridente 90 · Besta 55 · Arco 50

## Exemplos calculados

Valores produzidos pela configuração padrão; são impressos pelos testes
automatizados.

### Mesmo equipamento, encantamentos diferentes

| Peitoral de netherita (base 90) | Encantamentos | Total |
|---|---:|---:|
| sem encantamentos | 0 | **90** |
| Proteção IV | 150 | **240** |
| Proteção contra Explosões IV | 75 | **165** |
| Proteção contra Projéteis IV | 75 | **165** |
| Proteção contra Fogo IV | 60 | **150** |
| Espinhos III | 45 | **135** |
| Inquebrável III + Remendo | 27 | **117** |
| Proteção IV + Espinhos III + Inquebrável III + Remendo | 222 | **312** |

| Espada de netherita (base 80) | Encantamentos | Total |
|---|---:|---:|
| sem encantamentos | 0 | **80** |
| Afiação V | 155 | **235** |
| Aspecto Flamejante II | 45 | **125** |
| Julgamento V | 30 | **110** |
| Inquebrável III + Remendo | 27 | **107** |
| Repulsão II | 22 | **102** |
| Ruína dos Artrópodes V | 20 | **100** |
| Saque III | 6 | **86** |

| Outros | Encantamentos | Total |
|---|---:|---:|
| Mace (110) + Densidade V | 170 | **280** |
| Mace (110) + Ruptura IV | 120 | **230** |
| Mace (110) + Rajada de Vento III | 60 | **170** |
| Arco (50) + Força V | 120 | **170** |
| Arco (50) + Infinidade | 15 | **65** |
| Botas de netherita (45) + Peso-Pena IV | 40 | **85** |

### Conjuntos completos

| O que o jogador carrega | Armaduras | Arma escolhida | **Total** | Faixa |
|---|---:|---|---:|---|
| Ferro completo sem encantamentos + espada de ferro | 140 | espada de ferro, 45 | **185** | Aprendiz |
| Diamante Proteção II + espada de diamante Afiação II | 480 | espada de diamante, 115 | **595** | Guerreiro |
| Diamante Proteção IV + espada de diamante Afiação V | 820 | espada de diamante, 220 | **1040** | Elite |
| Netherita Prot. IV / Inq. III / Remendo + espada de netherita sem encantamentos | 973 | espada de netherita, 80 | **1053** | Elite |
| Netherita completa encantada¹ + espada completa² | 1046 | espada de netherita, 359 | **1405** | Mítico |
| O mesmo, carregando também machado, arco, besta e tridente | 1046 | espada de netherita, 359 | **1405** | Mítico |
| O mesmo, carregando também uma mace³ | 1046 | mace, 367 | **1413** | Mítico |
| O anterior + uma segunda armadura de netherita com Proteção IV guardada | 1046 | mace, 367 | **1413** | Mítico |
| Ferro vestido, a armadura de netherita encantada¹ guardada + espada completa² | 1046 | espada de netherita, 359 | **1405** | Mítico |

¹ Quatro peças com Proteção IV, Inquebrável III e Remendo; botas também com
Peso-Pena IV, Passos Profundos III e Velocidade das Almas III.
² Afiação V, Aspecto Flamejante II, Repulsão II, Alcance III, Saque III,
Inquebrável III e Remendo.
³ Densidade V, Rajada de Vento III, Inquebrável III, Remendo.

As linhas 5 a 8 mostram a regra: armas e armaduras a mais só mudam o resultado
quando uma delas é **melhor** que a escolhida (a mace, por 8 pontos). A última
linha mostra que a peça guardada vale tanto quanto a equipada.

## Faixas

| Faixa | A partir de |
|---|---:|
| Iniciante | 0 |
| Aprendiz | 150 |
| Guerreiro | 350 |
| Veterano | 600 |
| Elite | 900 |
| Lendário | 1200 |
| Mítico | 1400 |

Com a tabela padrão, o melhor conjunto possível fica perto de 1400. Ajuste
`tiers` ao perfil do servidor.

## Como ajustar

| Objetivo | Onde mexer |
|---|---|
| PvP com cristais/âncoras, valorizar explosões | subir `minecraft:blast_protection` em `rules.armor` |
| Dar mais peso ao material | subir `armor.materials` / `weapons` ou os multiplicadores de slot/tipo |
| Ignorar durabilidade | `category-multipliers.utility: 0.0` |
| Mace forte demais no ranking | baixar `weapons.type-multipliers.mace` ou a tabela de `density` |
| Servidor com níveis acima do vanilla | `above-max-level: allow` e definir `per-level` nos encantamentos |
| Encantamento de plugin/datapack | adicionar a chave com namespace no grupo do equipamento que o recebe |
| Um item não deve pontuar | adicioná-lo em `evaluation.ignored-items` |
| Mudar as faixas | `tiers` |

Depois de alterar pesos, aumente `rules-version` e use `/gearscore reload`. Os
jogadores online são reavaliados aos poucos; os offline são recalculados quando
entrarem novamente no servidor de origem oficial (o plugin não tem acesso ao
inventário de quem está offline e não estima resultados). A coluna
`rules_version` do banco mostra com qual tabela cada pontuação foi calculada.
