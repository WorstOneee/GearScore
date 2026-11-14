# Fórmula e balanceamento

O GearScore é uma **estimativa da força do equipamento**. Ele não mede habilidade,
poções, comida, totens nem posicionamento, e não deve ser lido como garantia de
vitória em PvP.

## Fórmula

```
GearScore = base das armaduras
          + encantamentos das armaduras
          + base da melhor arma
          + encantamentos dessa arma
```

Cada uma das quatro parcelas é arredondada para inteiro e o total é a soma delas,
então o que o `/gearscore` mostra sempre fecha com o detalhamento.

### O que é lido

| Origem | Slots | Observação |
|---|---|---|
| Armadura | capacete, peitoral, calças, botas | só o que está **equipado** |
| Arma | hotbar (9 slots) e mão secundária | só **uma** arma conta: a de maior pontuação |

O restante do inventário não é lido. Carregar nove espadas iguais vale o mesmo
que carregar uma. A arma escolhida aparece no `/gearscore` com o slot onde está.

### Pontuação de um item

```
item = (base + bônus de item personalizado) + soma dos encantamentos
```

* **base**: `armor.materials.<material>.<slot>` ou `weapons.<tipo>.<material>`,
  multiplicada por `armor.slot-multipliers` / `weapons.type-multipliers`.
* **encantamento**: `pontos do nível × multiplicador da categoria`.
* Um item nunca fica abaixo de zero, mesmo com maldições negativas.

### Pontos por nível

Cada encantamento tem a própria regra, por tipo de equipamento:

```yaml
"minecraft:protection": { category: combat, levels: [30, 65, 105, 150] }   # tabela
"minecraft:smite":      { category: mob, per-level: 6, max-level: 5 }      # linear
```

* `levels` é uma tabela: nível 1, nível 2, ...
* `per-level` é a progressão linear. Combinado com `levels`, vale para cada nível
  além do fim da tabela (usado apenas com `above-max-level: allow`).
* `max-level` é o limite. Se omitido: tamanho da tabela ou o máximo vanilla.

Não é a quantidade de encantamentos que conta, e sim quais são e em que nível.

### Categorias

| Categoria | Uso | Exemplos |
|---|---|---|
| `combat` | sempre útil contra jogadores | Proteção, Afiação, Força, Densidade, Ruptura |
| `conditional` | útil em situações específicas | proteções especializadas, Espinhos, Repulsão, Perfuração |
| `mob` | especializado em mobs | Julgamento, Ruína dos Artrópodes, Empalamento |
| `mobility` | mobilidade | Peso-Pena, Passos Profundos, Correnteza, Rajada de Vento |
| `utility` | durabilidade e utilidade | Inquebrável, Remendo, Infinidade, Saque |
| `curse` | maldições | Desaparecimento, Ligação |

`enchantments.category-multipliers` sobe ou desce uma categoria inteira. Exemplo:
`utility: 0.0` faz Inquebrável e Remendo deixarem de pontuar.

### Ordem de busca das regras

Para cada encantamento de um item, a regra é procurada em
`enchantments.rules`, da mais específica para a mais geral:

1. o tipo da peça (`helmet`, `boots`, `sword`, `axe`, `mace`, `trident`, `bow`, `crossbow`, `spear`)
2. a família (`armor` ou `weapon`)
3. `any`

Isso permite, por exemplo, dar a Afiação um valor na espada e outro no machado,
ou pontuar Peso-Pena só nas botas.

### Encantamentos desconhecidos

Um encantamento sem regra para aquele tipo de equipamento vale **zero**
(inclusive os de datapacks e de outros plugins). O `/gearscore diag` lista as
chaves que foram encontradas sem regra, e o `/gearscore detalhes` marca o
encantamento como "não configurado".

Apenas encantamentos reais do item são lidos. Brilho visual
(`enchantment_glint_override`), nome, lore e CustomModelData não geram pontos.

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

Ficam em `enchantments.rules.any` com a categoria `curse`. O valor pode ser
negativo (penaliza), `0` (neutro) ou positivo. Padrão: −10 cada.

### Itens personalizados

Opcional (`custom-items.enabled`). Um item só é tratado como especial se tiver a
chave configurada no **PersistentDataContainer**. Somente as chaves listadas na
configuração são consultadas.

```
item = (base ou base substituída + bonus + encantamentos) × multiplier
```

## Tabela padrão

### Base das armaduras

| Material | Capacete | Peitoral | Calças | Botas | Conjunto |
|---|---:|---:|---:|---:|---:|
| Couro | 12 | 21 | 17 | 10 | 60 |
| Ouro | 18 | 32 | 26 | 14 | 90 |
| Cobre | 19 | 33 | 28 | 15 | 95 |
| Malha | 22 | 38 | 32 | 18 | 110 |
| Ferro | 28 | 48 | 42 | 22 | 140 |
| Diamante | 45 | 75 | 65 | 35 | 220 |
| Netherita | 55 | 90 | 75 | 45 | 265 |

### Base das armas

| Tipo | Madeira | Ouro | Pedra | Cobre | Ferro | Diamante | Netherita |
|---|---:|---:|---:|---:|---:|---:|---:|
| Espada | 20 | 22 | 30 | 35 | 45 | 65 | 80 |
| Machado | 22 | 22 | 32 | 36 | 46 | 62 | 76 |
| Lança | 15 | 18 | 22 | 26 | 34 | 48 | 60 |

Mace 110 · Tridente 90 · Besta 55 · Arco 50

### Encantamentos principais

| Equipamento | Encantamento | Pontos por nível |
|---|---|---|
| Armadura | Proteção | 30 / 65 / 105 / 150 |
| Armadura | Prot. contra Explosões, contra Projéteis | 15 / 32 / 52 / 75 |
| Armadura | Proteção contra Fogo | 12 / 26 / 42 / 60 |
| Armadura | Espinhos | 15 / 30 / 45 |
| Armadura | Peso-Pena | 10 / 20 / 30 / 40 |
| Qualquer | Inquebrável | 5 / 10 / 15 |
| Qualquer | Remendo | 12 |
| Espada, machado, lança | Afiação | 25 / 50 / 80 / 115 / 155 |
| Espada, mace, lança | Aspecto Flamejante | 25 / 45 |
| Espada, lança | Repulsão | 12 / 22 |
| Espada | Alcance | 8 / 16 / 24 |
| Espada, machado, mace, lança | Julgamento | 6 por nível |
| Espada, machado, mace, lança | Ruína dos Artrópodes | 4 por nível |
| Mace | Densidade | 30 / 60 / 95 / 130 / 170 |
| Mace | Ruptura | 30 / 60 / 90 / 120 |
| Mace | Rajada de Vento | 20 / 40 / 60 |
| Arco | Força | 20 / 40 / 65 / 90 / 120 |
| Arco | Chama 25 · Impacto 10 / 20 · Infinidade 30 | |
| Besta | Carga Rápida | 20 / 40 / 60 |
| Besta | Perfuração 8 / 16 / 24 / 32 · Tiro Múltiplo 25 | |
| Tridente | Lealdade 15 / 25 / 35 · Correnteza 20 / 35 / 50 | |
| Tridente | Canalização 10 · Empalamento 5 por nível | |

A lista completa está em `config.yml`.

## Exemplos numéricos

Valores produzidos pela configuração padrão (são os mesmos casos verificados
nos testes automatizados).

| Equipamento | Base armadura | Enc. armadura | Base arma | Enc. arma | **Total** | Faixa |
|---|---:|---:|---:|---:|---:|---|
| Netherita completa, sem encantamentos, sem arma | 265 | 0 | 0 | 0 | **265** | Aprendiz |
| Ferro completo sem encantamentos + espada de ferro | 140 | 0 | 45 | 0 | **185** | Aprendiz |
| Diamante Proteção II + espada de diamante Afiação II | 220 | 260 | 65 | 50 | **595** | Guerreiro |
| Diamante Proteção IV + espada de diamante Afiação V | 220 | 600 | 65 | 155 | **1040** | Elite |
| Netherita Prot. IV / Inq. III / Remendo + espada de netherita sem encantamentos | 265 | 708 | 80 | 0 | **1053** | Elite |
| Netherita completa encantada¹ + espada completa² | 265 | 781 | 80 | 282 | **1408** | Mítico |
| Netherita completa encantada¹ + mace Densidade V / Rajada de Vento III / Inq. III / Remendo | 265 | 781 | 110 | 257 | **1413** | Mítico |
| Netherita completa encantada¹ + arco Força V / Chama / Impacto II / Infinidade / Inq. III | 265 | 781 | 50 | 210 | **1306** | Lendário |

¹ Quatro peças com Proteção IV, Inquebrável III e Remendo; botas também com
Peso-Pena IV, Passos Profundos III e Velocidade das Almas III.
² Afiação V, Aspecto Flamejante II, Repulsão II, Alcance III, Saque III,
Inquebrável III e Remendo.

### Conta passo a passo

Peitoral de netherita com Proteção IV, Inquebrável III e Remendo:

```
base                    90
Proteção IV            150   (combat  × 1.0)
Inquebrável III         15   (utility × 1.0)
Remendo                 12   (utility × 1.0)
                       ----
item                   267
```

Espada de netherita com Afiação V e Aspecto Flamejante II:

```
base                    80
Afiação V              155
Aspecto Flamejante II   45
                       ----
item                   280
```

### Leituras úteis para ajustar

* Encantamentos dominam: a netherita completa vai de **265** (sem
  encantamentos) para **973** só com Proteção IV, Inquebrável III e Remendo.
* Proteção IV em uma peça (150) vale mais que a diferença de base entre
  diamante e netherita no conjunto inteiro (45).
* Julgamento V (30) vale cerca de um quinto de Afiação V (155): a espada
  "de mob" não aparece como forte em PvP.
* Remendo + Inquebrável III somam 27 por peça, contra 150 de Proteção IV.

## Como ajustar

| Objetivo | Onde mexer |
|---|---|
| PvP com cristais/âncoras, valorizar explosões | subir `minecraft:blast_protection` em `rules.armor` |
| Dar mais peso ao material | subir `armor.materials` / `weapons` ou os multiplicadores de slot/tipo |
| Ignorar durabilidade | `category-multipliers.utility: 0.0` |
| Mace forte demais no ranking | baixar `weapons.type-multipliers.mace` ou a tabela de `density` |
| Servidor com níveis acima do vanilla | `above-max-level: allow` e definir `per-level` nos encantamentos |
| Encantamento de plugin/datapack | adicionar a chave com namespace no grupo do equipamento |
| Mudar as faixas | `tiers` |

Depois de alterar pesos, aumente `rules-version` e use `/gearscore reload`. Os
jogadores online são reavaliados aos poucos; os offline são atualizados quando
entrarem novamente em um servidor de origem oficial. A coluna `rules_version`
do banco mostra com qual tabela cada pontuação foi calculada.
