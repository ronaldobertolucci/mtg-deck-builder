# MTG Deck Builder

Microsserviço em Java e Spring Boot para criar, organizar e analisar decks de
Magic: The Gathering. O serviço mantém os decks de cada usuário no PostgreSQL,
autentica requisições com JWT e consulta o [MTG Card Manager](https://github.com/ronaldobertolucci/mtg-card-manager) para obter metadados e
legalidades das cartas pela identidade estável `oracle_id`.

O projeto está em desenvolvimento. O MVP suporta **STANDARD, MODERN, PIONEER,
LEGACY e COMMANDER**, incluindo múltiplos comandantes compatíveis e uma zona
opcional de companion. As condições específicas de construção de cada companion
ainda não são verificadas.

## Tecnologias

- Java 25 e Spring Boot 4.1.1
- Spring Web MVC, Spring Security e JWT com Auth0 Java JWT
- Spring Data JPA, Hibernate e Bean Validation
- PostgreSQL 16 e Flyway
- Spring RestClient e Caffeine Cache
- Jakarta Mail para confirmação de e-mail e recuperação de senha
- Springdoc OpenAPI e Swagger UI
- JUnit 5, Mockito, MockMvc e Testcontainers
- H2 para parte dos testes
- Maven Wrapper e Docker Compose

## Autenticação, refresh e expiração

O login (`POST /api/auth/login`) retorna `token`, `type` (Bearer), `expiresIn` em
segundos e `user`. O JWT continua válido por 2 horas por padrão
(`api.security.token.expiration-hours`). Login e refresh também enviam o cookie
`mtg_refresh`, usado exclusivamente para renovar o acesso e encerrar a sessão.
O refresh token não é exposto no JSON nem deve ser armazenado no localStorage.

| Endpoint | Entrada | Resultado |
| --- | --- | --- |
| `POST /api/auth/login` | JSON com `email` e `password` | JWT e novo cookie de refresh. |
| `POST /api/auth/refresh` | Cookie `mtg_refresh`; sem body e sem necessidade de Bearer | Novo JWT e troca do cookie. |
| `POST /api/auth/logout` | Cookie `mtg_refresh` | Revoga a sessão, apaga o cookie e retorna 204. Sem cookie, também retorna 204. |

Os três endpoints exigem `X-CSRF-Protection: 1`. Esse header força preflight em
requisições entre origens; CORS aceita credenciais somente das origens explícitas
configuradas em `cors.allowed-origins`. Não usar curingas ou origens não confiáveis.
O navegador deve usar `credentials: "include"`. Requisições sem o header retornam
403 e não executam a operação. Clientes de API também devem enviar o header.

```javascript
const response = await fetch(`${apiUrl}/auth/refresh`, {
  method: "POST",
  credentials: "include",
  headers: { "X-CSRF-Protection": "1" }
});
```

O cookie é `HttpOnly`, `SameSite=Strict`, sem Domain, com path `/api/auth` (acompanha
`server.servlet.context-path`) e `Secure` por padrão. O profile `dev` desabilita
`Secure` para HTTP local. A configuração atual pressupõe frontend e API no mesmo
site (por exemplo, subdomínios HTTPS do mesmo domínio), ainda que em origens diferentes.
Hospedar o frontend em outro site exige revisar SameSite e a política de CSRF.

### Ciclo da sessão

- Cada login cria uma sessão independente, com validade absoluta de 7 dias
  (`api.security.refresh.expiration-days`). Renovar não estende esse prazo.
- Tokens opacos usam 32 bytes aleatórios; o banco armazena apenas hashes SHA-256.
- Cada refresh troca o token sob bloqueio da sessão no banco. Reutilizar qualquer
  token anterior revoga toda aquela sessão, inclusive seu token mais recente.
- Logout revoga a sessão correspondente ao cookie. Redefinir a senha revoga todas
  as sessões de refresh do usuário. Contas desabilitadas não podem renovar.
- Tokens antigos são retidos até a expiração absoluta para detectar reutilização.
  A limpeza diária remove sessões expiradas e seu histórico
  (`api.security.refresh.cleanup-cron`, padrão `0 30 2 * * ?`).
- Login, refresh e logout retornam `Cache-Control: no-store`.

**Limite da revogação:** JWTs já emitidos continuam válidos até sua expiração;
logout e redefinição de senha impedem futuras renovações. O cliente deve apagar
seu JWT no logout. Revogação imediata de JWT exigiria validação de sessão ou versão
em cada requisição; não faz parte deste fluxo.

### Integração com frontend

Este repositório contém apenas a API. O frontend deverá guardar o JWT em memória e
usar `expiresIn` para acompanhar sua validade. Ao receber 401 de um recurso protegido,
tentar uma única renovação. Centralizar e coordenar essa operação, inclusive entre
abas: duas renovações com o mesmo token serão interpretadas como reutilização.
Não aplicar a renovação automática às próprias rotas de login, refresh e logout.

Se a renovação funcionar, atualizar o JWT. Repetir apenas requisições que possam ser
reexecutadas com segurança; não reenviar operações de escrita indiscriminadamente.
Se o refresh retornar 401, limpar a autenticação, preservar a rota interna e o rascunho
do deck vinculado ao ID do usuário e solicitar novo login. Restaurar o rascunho somente
para o mesmo usuário. Falha de rede ou 5xx não equivale a expiração: manter o rascunho
e informar a indisponibilidade, sem entrar em ciclo de tentativas.

Requisições protegidas distinguem os seguintes códigos:

| HTTP | `code` | Significado |
| --- | --- | --- |
| 401 | `SESSION_EXPIRED` | JWT expirado; em `/auth/refresh`, sessão ausente, inválida, expirada ou revogada. |
| 401 | `INVALID_TOKEN` | JWT inválido. |
| 401 | `AUTHENTICATION_REQUIRED` | Recurso exige autenticação. |
| 403 | `ACCESS_DENIED` | Falta de permissão ou header CSRF ausente; não encerrar automaticamente a sessão. |

Falha de refresh também apaga o cookie. Credenciais incorretas no login retornam
401 com a mensagem de erro de login. Rotas públicas continuam disponíveis com um
Bearer expirado. Decks já persistidos continuam disponíveis após um novo login.

### Estado da conta, confirmação de e-mail e rejeições de login

O modelo separa `users.enabled` (habilitação administrativa) de
`users.email_verified` (confirmação de e-mail). O acesso exige os dois valores
verdadeiros. Novos cadastros começam com `enabled=true`, `email_verified=false`.
Confirmar um token válido altera apenas `email_verified`; habilitar ou desabilitar
uma conta altera apenas `enabled`. O campo `enabled` do JSON `UserDto` continua
indicando acesso efetivo (a conjunção dos dois), preservando o formato do login.

Após validar a senha, `POST /api/auth/login` distingue:

| HTTP | `code` | Estado | Orientação para o frontend |
| --- | --- | --- | --- |
| 403 | `EMAIL_NOT_VERIFIED` | Conta habilitada, e-mail não confirmado. | Solicitar confirmação e oferecer reenvio. |
| 403 | `ACCOUNT_DISABLED` | Conta desabilitada, independentemente da confirmação. | Informar bloqueio e orientar contato com suporte; não oferecer reenvio como solução. |

Ambas as respostas usam `Content-Type: application/json` e `Cache-Control: no-store`.
Exemplo anonimizado de e-mail pendente:

```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "EMAIL_NOT_VERIFIED",
  "message": "Email address has not been verified",
  "path": "/api/auth/login"
}
```

Para desabilitação, o mesmo formato contém `code: "ACCOUNT_DISABLED"` e
`message: "Account is not enabled for sign-in"`. O frontend deve decidir por `code`,
não comparar `message`, e não tratar esses 403 como indisponibilidade ou expiração.
Se ambos os impedimentos existem, `ACCOUNT_DISABLED` tem prioridade.

O estado só é informado depois de validar a senha. E-mail inexistente ou senha
incorreta (inclusive para contas pendentes ou desabilitadas) preservam o contrato
anterior: 401, `error: "Unauthorized"`, `message: "Invalid email or password"`, sem
`code` de estado. O provider mantém BCrypt e a proteção de tempo para usuário
inexistente. Todas as verificações de estado ocorrem antes de concluir a autenticação.
Rejeições não criam JWT, sessão de refresh ou cookie. Falhas internas do serviço de
autenticação permanecem 500, sem serem convertidas em erros de estado da conta.
Login válido, refresh, logout e o requisito de CSRF mantêm seus contratos. Contas
sem confirmação também não podem renovar sessões.

`POST /api/auth/resend-verification`, com JSON `{"email":"person@example.com"}`,
só envia e-mail quando `enabled=true` e `email_verified=false`. Para e-mail
inexistente, já confirmado ou conta desabilitada, retorna o mesmo **200 sem corpo**,
sem enviar mensagem nem criar token. Isso evita divulgar o estado por esse endpoint
público. Erros de validação continuam 400 e falhas internas de envio não são ocultadas.
Um token anteriormente emitido ainda pode confirmar o e-mail de uma conta desabilitada,
mas não reabilita o acesso. Nenhuma confirmação emite JWT ou sessão.

A rotação segue a orientação de detecção de reutilização do
[RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2), e a proteção
por header e CORS segue a [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#employing-custom-request-headers-for-ajaxapi).

## Modelo de dados e identidade das cartas

Os dados de autenticação e os decks são persistidos no PostgreSQL. Os metadados das
cartas permanecem no Card Manager; o Deck Builder guarda referências por `oracle_id`.

### `decks`

Representa um deck pertencente a um usuário:

| Campo | Descrição |
| --- | --- |
| `id` | UUID gerado para o deck. |
| `user_id` | Identificador do usuário autenticado. |
| `name` | Nome do deck, até 255 caracteres. |
| `format` | STANDARD, MODERN, PIONEER, LEGACY ou COMMANDER. |
| `status` | REGULAR, IRREGULAR ou UNDEFINED. |
| `analyzed_at` | Data da última análise válida para o conteúdo atual, ou nulo. |
| `created_at`, `updated_at` | Datas de criação e atualização. |

Decks novos começam com status `UNDEFINED`. As explicações da última análise são
persistidas em `deck_analysis_messages`, em ordem, vinculadas ao deck.

### `deck_cards`

Cada registro representa a quantidade de uma carta em uma zona:

| Campo | Descrição |
| --- | --- |
| `id` | UUID gerado para o registro. |
| `deck_id` | Referência ao deck, com exclusão em cascata. |
| `oracle_id` | UUID estável da carta no catálogo. Não é o ID de uma impressão. |
| `quantity` | Quantidade positiva. Na zona COMPANION, deve ser 1. |
| `board_type` | MAINBOARD, COMMANDER, SIDEBOARD, COMPANION ou TOKENS. |
| `is_auto_generated` | Origem automática do acessório; padrão false, permitida somente em TOKENS. |

A restrição única `(deck_id, oracle_id, board_type)` impede registros duplicados da
mesma carta na mesma zona. Uma carta pode aparecer em zonas diferentes, mas seus
limites de cópias são avaliados em conjunto. Quantidade zero é um comando de remoção
na API; não é persistida.

As migrações também criam usuários, papéis, vínculos de autorização e tokens para
confirmação de e-mail e recuperação de senha.

## Execução com Docker

### Pré-requisitos

- Docker com o plugin Docker Compose
- MTG Card Manager acessível pela rede do contêiner e com catálogo carregado
- Serviço SMTP para os fluxos de cadastro e recuperação de senha
- Arquivo `.env` preenchido para o profile `prod`, usado pelo Compose

### Preparar o ambiente

Se ainda não existir um `.env`, copie o modelo e substitua seus valores de exemplo:

```bash
cp .env.example .env
```

### Acesso ao Card Manager: host e redes Docker

```dotenv
CARD_MANAGER_URL={URL que aponta para o MTG Card Manager}
CARD_MANAGER_ORACLE_DETAILS_PATH=/cards/{oracleId}
```

### Iniciar API e PostgreSQL

```bash
docker compose up --build -d --remove-orphans
```

| Recurso | Nome |
| --- | --- |
| API | `mtg-deck-builder-app` |
| PostgreSQL | `mtg-deck-builder-db` |
| Volume lógico | `postgres-data` |
| Rede | Rede padrão criada pelo Compose para o projeto |

A API fica disponível em `http://localhost:8080/api`. O Compose mapeia
`127.0.0.1:8080` do host para a porta `8080` do contêiner da aplicação, permitindo
acesso somente pela máquina local. A porta 5432 do banco não é publicada.
O nome efetivo do volume recebe o prefixo do projeto Compose.

A opção `--remove-orphans` remove contêineres de serviços que deixaram de fazer
parte do Compose ao atualizar uma instalação existente.

Flyway aplica as migrações pendentes durante a inicialização. Swagger UI e OpenAPI
ficam desabilitados no profile `prod`. Não há endpoint dedicado de health check
implementado atualmente.

```bash
docker compose logs -f app
docker compose down
```

### Recriar o banco de desenvolvimento

Normalmente, as alterações do schema são aplicadas pelo Flyway. Para descartar
intencionalmente todos os dados locais e começar novamente:

```bash
docker compose down --volumes --remove-orphans
docker compose up --build -d
```

> `docker compose down --volumes` apaga os dados do PostgreSQL, incluindo usuários,
> decks e tokens persistidos.

## Configuração

A configuração usa `application.properties` e arquivos específicos para `dev`,
`test` e `prod`. O `.env` é utilizado pelo Docker Compose; ao executar Java ou Maven
diretamente, exporte as variáveis necessárias no shell ou configure-as na IDE.

| Variável | Padrão ou uso | Descrição |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` no Compose | Profile da aplicação; use `dev` na execução local. |
| `SERVER_PORT` | `8080` | Porta HTTP interna. O contexto da aplicação é `/api`. |
| `DATASOURCE_URL` | Obrigatória em prod | URL JDBC do PostgreSQL. |
| `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD` | Obrigatórias em prod | Credenciais JDBC. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Exigidas pelo Compose | Inicialização do contêiner PostgreSQL. |
| `API_SECURITY_TOKEN_SECRET` | Obrigatória em prod | Segredo de assinatura JWT. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` em dev | Origens autorizadas pelo CORS. |
| `CARD_MANAGER_URL` | `http://localhost:8000` em dev; vazio na configuração comum | URL base do Card Manager. |
| `CARD_MANAGER_ORACLE_DETAILS_PATH` | `/cards/{oracleId}` | Caminho de consulta; deve conter `{oracleId}`. |
| `APP_EMAIL_FROM` | `dev@localhost` em dev | Remetente dos e-mails. |
| `APP_FRONTEND_URL` | `http://localhost:4200` em dev | URL do frontend usada nos links dos e-mails. |
| `APP_NAME` | `MTG-Deck-Builder-DEV` em dev | Nome da aplicação nos e-mails. |
| `APP_EMAIL_VERIFICATION_EXPIRY_HOURS` | `24` em dev | Validade do token de confirmação. |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` em dev | Servidor SMTP. |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Vazios em dev | Credenciais SMTP. |
| `MAIL_AUTH`, `MAIL_STARTTLS` | `false` em dev | Autenticação e STARTTLS; use valores booleanos. |
| `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` | `24` em dev | Validade do token de recuperação. |
| `PASSWORD_RESET_CLEANUP_CRON` | `-` em dev | Agendamento de limpeza; `-` desabilita a execução. |

Os valores de dev são definidos no arquivo do profile; as variáveis personalizadas
de e-mail e banco acima são usadas como placeholders em prod. Para sobrescrever
propriedades locais, use os parâmetros do Spring ou as variáveis correspondentes,
como `SPRING_DATASOURCE_URL`.

O JWT possui validade configurada de duas horas. O cache `cards` usa Caffeine com
até 10.000 entradas e expiração de 24 horas após o último acesso. A configuração
atual do Compose só encaminha as variáveis declaradas em `environment`; acrescente
outras nesse bloco ou em um override quando necessário.

## Integração com o Card Manager

O Deck Builder consulta o serviço responsável pelo catálogo:

```http
GET /cards/{oracleId}?lang=en
```

O parâmetro `lang=en` é sempre enviado porque as regras de override e de comandantes
interpretam os textos oficiais em inglês. O DTO utiliza `oracle_id`, `name`,
`type_line`, `oracle_text`, `color_identity`, `legalities`, `keywords`, `layout` e
`card_faces` (nome, tipo e texto de cada face).

1. Na criação de Commander e na inclusão ou atualização de cartas, o serviço obtém
   os metadados usando cache por oracle ID.
2. A integração converte falhas HTTP 4xx em carta não encontrada e falhas 5xx ou de
   comunicação em indisponibilidade do Card Manager.
3. Na análise completa, cada oracle ID é consultado novamente, sem reutilizar a
   entrada anterior do cache; uma resposta bem-sucedida atualiza esse cache.

Este microsserviço não importa o Bulk Data nem sincroniza diretamente com o
Scryfall. A atualização do catálogo é responsabilidade do MTG Card Manager. Uma
consulta nova reflete os dados atualmente disponíveis nesse serviço.

## API

Base local e pelo Compose: `http://localhost:8080/api`.
Os caminhos abaixo incluem o contexto `/api` uma única vez.

Todos os endpoints de decks, incluindo o de análise, exigem autenticação:

```http
Authorization: Bearer <token>
```

Nas requisições com corpo JSON, envie também `Content-Type: application/json`.
O proprietário é obtido do usuário autenticado. Não envie `user_id` no corpo.

### Autenticação e conta

| Método | Caminho | Finalidade |
| --- | --- | --- |
| POST | `/api/auth/register` | Cadastrar usuário e iniciar confirmação de e-mail. |
| GET | `/api/auth/verify-email?token=...` | Confirmar e-mail. |
| POST | `/api/auth/resend-verification` | Reenviar confirmação, com `email` no corpo. |
| POST | `/api/auth/login` | Autenticar com `email` e `password`; emitir JWT e cookie de refresh. |
| POST | `/api/auth/refresh` | Renovar JWT e rotacionar o cookie de refresh. |
| POST | `/api/auth/logout` | Revogar a sessão de refresh e apagar o cookie. |
| POST | `/api/password/forgot` | Solicitar recuperação, com `email` no corpo. |
| GET | `/api/password/reset/validate?token=...` | Validar token de recuperação. |
| POST | `/api/password/reset` | Redefinir senha, com `token` e `newPassword`. |

Exemplo de cadastro:

```json
{
  "firstName": "Maria",
  "lastName": "Silva",
  "email": "maria@example.com",
  "dateOfBirth": "1995-01-20",
  "password": "senha-de-exemplo-123"
}
```

O cadastro retorna `201 Created`. Após confirmar o e-mail, faça login:

```json
{
  "email": "maria@example.com",
  "password": "senha-de-exemplo-123"
}
```

A resposta do login contém `token`, `type` (Bearer), `expiresIn` em segundos e `user`.
Use o token nas próximas requisições.

## Criação e edição de decks

### Listar, consultar, renomear e excluir

Todas as operações exigem autenticação e acessam somente decks do usuário autenticado.
Deck inexistente ou pertencente a outro usuário retorna 404 nas operações individuais.

| Método | Caminho | Resposta |
| --- | --- | --- |
| GET | `/api/decks?page=0&size=20` | 200 com resumos paginados do usuário. |
| GET | `/api/decks/{deckId}` | 200 com `DeckResponse`, incluindo cartas e análise. |
| PATCH | `/api/decks/{deckId}` | 200 com o deck renomeado. Corpo: `{"name":"Novo nome"}`. |
| DELETE | `/api/decks/{deckId}` | 204 sem corpo; remove também cartas e mensagens da análise. |

A listagem retorna `content` e `page` (`size`, `number`, `totalElements`, `totalPages`).
Cada resumo contém `id`, `name`, `format`, `createdAt`, `updatedAt`, `status` e `analyzedAt`,
sem carregar cartas e mensagens. A ordenação é por `updatedAt` decrescente, com desempate
por `id` decrescente. `page` começa em zero; `size` aceita 1 a 100 (padrão 20).
Parâmetros inválidos retornam 400. Sem resultados, `content` é uma lista vazia.

O nome é obrigatório, não pode ser branco e aceita até 255 caracteres. Renomear
preserva formato, cartas e análise, e atualiza `updatedAt` quando o nome muda.
A edição não oferece troca de formato. Uma segunda exclusão retorna 404.
Essas operações não dependem do Card Manager.

### Estatísticas do deck

`GET /api/decks/{deckId}/stats` retorna HTTP 200 com `totalCards`, `averageCmc`,
`manaCurve`, `typeDistribution`, `colorPips`, `rarityDistribution` e `cardsByType`. Exige autenticação
e propriedade do deck; deck inexistente ou de outro usuário retorna 404.

Somente MAINBOARD e COMMANDER participam das estatísticas, com contagens ponderadas
pela quantidade de cópias. Terrenos entram no total, nos tipos e nas raridades,
mas ficam fora da curva, dos pips e do CMC médio. A média usa o CMC original e é
arredondada para duas casas decimais; sem cartas não-terreno, retorna zero.
A curva arredonda o CMC para inteiro e contém sempre `0` a `6` e `7+`.

Cada carta pertence a um único tipo, seguindo a prioridade Land, Creature,
Planeswalker, Instant, Sorcery, Artifact, Enchantment e Other. Os pips contam
cada ocorrência de W, U, B, R, G e C por bloco `{...}` do custo de mana, uma vez
por cor no bloco e multiplicada pela quantidade de cópias, com chaves WHITE,
BLUE, BLACK, RED, GREEN e COLORLESS. Híbridos contam para ambas as cores:
`{G/W}` soma GREEN e WHITE; `{B/P}` soma BLACK; `{2/W}` soma WHITE e `{C}` soma
COLORLESS. Blocos genéricos como `{1}`, `{2}` e `{X}` não somam pips. As raridades usam COMMON,
UNCOMMON, RARE e MYTHIC. Todas as categorias são inicializadas com zero.
Os metadados são consultados pelo oracle ID, aproveitando o cache do Card Manager.

`cardsByType` contém as mesmas oito chaves de `typeDistribution`, com listas vazias
para grupos sem cartas. Cada entrada informa `oracleId`, `boardType` e `quantity`,
usando a mesma classificação das contagens. A soma de `quantity` de cada grupo
é igual ao respectivo valor em `typeDistribution`. Uma carta presente em MAINBOARD
e COMMANDER aparece em entradas separadas, preservando a quantidade em cada zona.
Imagens e traduções continuam sendo obtidas pelo frontend no Card Manager.

Exemplo de grupo dentro de `cardsByType`:

```json
{
  "Creature": [
    {
      "oracleId": "123e4567-e89b-12d3-a456-426614174000",
      "boardType": "MAINBOARD",
      "quantity": 4
    }
  ],
  "Land": []
}
```

### Composição por zona e tipo

`GET /api/decks/{deckId}/composition` retorna HTTP 200 com `cardsByBoardAndType`.
Exige autenticação e propriedade do deck; retorna 404 para deck inexistente ou
de outro usuário. É uma consulta da composição inteira: MAINBOARD, COMMANDER,
SIDEBOARD, COMPANION e TOKENS, incluindo acessórios manuais e automáticos.

Cada zona contém listas para Creature, Instant, Sorcery, Artifact, Enchantment,
Planeswalker, Land, Other, Token, Emblem e Dungeon. Todas as zonas e grupos
aparecem, mesmo vazios (`[]`). Cada registro aparece em exatamente um grupo da
sua zona, preservando os campos da composição: `id`, `oracleId`, `boardType`,
`quantity` e `isAutoGenerated`. O mesmo oracle ID em zonas diferentes mantém
entradas independentes. A ordem das cartas dentro de cada lista não é garantida.

A classificação de cartas comuns usa a mesma prioridade das estatísticas.
Acessórios têm precedência: layouts `token` e `double_faced_token` vão para Token;
layout `emblem` vai para Emblem; os demais tipos contendo `Dungeon` vão para Dungeon.
Assim, uma ficha de criatura artefato fica em Token, sem perder sua classificação
de acessório. Metadados ausentes de tipo e layout resultam em Other.

Trecho de uma resposta (as demais zonas e grupos também são retornados):

```json
{
  "cardsByBoardAndType": {
    "SIDEBOARD": {
      "Creature": [
        {
          "id": "123e4567-e89b-12d3-a456-426614174001",
          "oracleId": "123e4567-e89b-12d3-a456-426614174000",
          "boardType": "SIDEBOARD",
          "quantity": 2,
          "isAutoGenerated": false
        }
      ]
    },
    "TOKENS": {
      "Token": [],
      "Emblem": [],
      "Dungeon": []
    }
  }
}
```

Essa consulta não modifica o deck nem amplia o escopo de `/stats`: suas contagens
e `cardsByType` continuam restritos a MAINBOARD e COMMANDER. Imagens e traduções
continuam vindo do Card Manager; a composição consulta os metadados em cache
por oracle ID, uma vez por ID distinto na requisição.

### Sugestão de base de mana

`GET /api/decks/{deckId}/mana-suggestion?targetLands=36` retorna HTTP 200:

```json
{"suggestedBasicLands":{"WHITE":0,"BLUE":18,"BLACK":0,"RED":0,"GREEN":18}}
```

`targetLands` é inteiro, opcional (padrão 36) e não negativo; valores inválidos
retornam 400. Exige autenticação e propriedade do deck, com 404 para deck
inexistente ou de outro usuário. Usa os metadados em cache de MAINBOARD e COMMANDER.

> Plataformas como EDHREC, Moxfield e Archidekt adotam 36 como a constante neutra padrão porque ela atende a maioria 
esmagadora dos decks com curva de mana média entre 2.5 e 3.5.

A demanda conta pips coloridos por cópia, incluindo ambas as cores dos híbridos.
Terrenos não participam. Cada cor de `produced_mana` abate uma unidade por cópia
somente para geradores não-terreno com `cmc >= 2`. Geradores com CMC menor que 2
ou desconhecido não reduzem a demanda. A demanda líquida de cada cor nunca é negativa.
O algoritmo do maior resto distribui exatamente `targetLands`, com desempate na
ordem WHITE, BLUE, BLACK, RED, GREEN. Havendo demanda, todas essas chaves aparecem,
inclusive as de quantidade zero. Sem demanda líquida, retorna
`{"suggestedBasicLands":{}}`. A sugestão não modifica o deck.

### Exportar deck

`GET /api/decks/{deckId}/export?format=ARENA` retorna HTTP 200 com `ExportDeckResponse`:

```json
{
  "content": "Commander\n1 Ghalta, Primal Hunger\n\nDeck\n99 Forest"
}
```

O parâmetro `format` é opcional e aceita `ARENA` (padrão) ou `PLAIN_TEXT`.
Arena usa os blocos Commander, Companion, Deck e Sideboard, omitindo zonas vazias e TOKENS.
Texto puro começa pelo Mainboard sem cabeçalho; Commander, Companion, Sideboard e Tokens
mantêm cabeçalhos para preservar suas zonas. Blocos são separados por uma linha
em branco, sem quebra de linha final. As cartas são ordenadas por nome dentro de cada zona.
Para reimportar uma exportação em texto puro que contenha `Tokens`, remova esse
bloco e suas linhas: a importação rejeita essa zona e gera os acessórios automaticamente.

Nos dois formatos, cartas de duas faces são exportadas somente pelo nome da face
frontal (`card_faces[0].name`). Por exemplo, Esika gera `1 Esika, God of the Tree`,
sem ` // The Prismatic Bridge`. Se os dados da face frontal estiverem ausentes ou
incompletos, o serviço usa a parte anterior a `//` do nome completo. Tokens de duas
faces seguem a mesma regra no texto puro. Cartas multiface de um só lado, como
split, adventure e flip, mantêm o nome completo.

Exige autenticação e propriedade do deck; deck inexistente ou de outro usuário
retorna 404. O serviço resolve nomes por oracle ID no Card Manager usando o cache
existente. Um deck vazio retorna `content` vazio; a exportação não altera nem analisa o deck.

### Consultar cartas para o Printing

`GET /api/decks/{deckId}/print-cards` recebe o UUID do deck no caminho, sem corpo
e sem parâmetros de consulta. Exige `Authorization: Bearer <token>` do proprietário
e retorna `200 OK` com JSON (`PrintDeckResponse`):

```json
{
  "cards": [
    {"oracleId": "b2c6aa39-2d2a-459c-a555-fb48ba993373", "quantity": 5}
  ]
}
```

Cada item contém `oracleId` (UUID da identidade da carta, não de uma impressão)
e `quantity` (inteiro positivo). A lista inclui todas as zonas persistidas:
MAINBOARD, COMMANDER, SIDEBOARD, COMPANION e TOKENS, incluindo acessórios automáticos.
Ocorrências do mesmo oracle ID em zonas diferentes são consolidadas, somando as
quantidades; por exemplo, 2 no MAINBOARD e 3 no SIDEBOARD resultam em `quantity: 5`.
As zonas e a origem automática não são expostas na resposta.

Deck vazio retorna `{"cards":[]}`. A resposta contém a lista completa, sem paginação;
a paginação das opções ocorre no Printing que consome o endpoint. A consulta não acessa o Card Manager,
não resolve nomes, imagens ou IDs de impressão e não altera nem analisa o deck.
Deck inexistente ou pertencente a outro usuário retorna `404 Not Found`.
Sem autenticação, a API retorna `401 Unauthorized` com `code: AUTHENTICATION_REQUIRED`.

### Importar deck de texto

`POST /api/decks/import` recebe o nome, formato e texto da lista:

```json
{
  "name": "Meu deck Modern",
  "format": "MODERN",
  "rawText": "Deck\n4 Lightning Bolt (M11) 146\nSideboard\n2 Duress"
}
```

Requer autenticação e retorna `201 Created`, `Location` e o deck com suas cartas.
Cada linha usa `quantidade nome`; os dados de coleção do Arena são descartados.
Os cabeçalhos `Deck`, `Maindeck`, `Sideboard`, `Commander` e `Companion` aceitam
maiúsculas/minúsculas e dois-pontos opcionais. Sem cabeçalho, a zona é MAINBOARD.
Linhas vazias são ignoradas; linhas inválidas e quantidades não positivas são rejeitadas.
Linhas repetidas da mesma carta e zona têm as quantidades somadas.

O cabeçalho `Tokens` não é aceito, mesmo com a zona vazia. Sua presença rejeita
toda a importação com `422`, `code: "IMPORT_TOKENS_NOT_SUPPORTED"`,
`field: "rawText"` e `line` apontando para o cabeçalho. A rejeição também cobre
variações de maiúsculas/minúsculas, espaços externos e dois-pontos opcionais;
nenhum deck é criado nem são feitas consultas ao Card Manager.
As cartas importadas em MAINBOARD, COMMANDER, SIDEBOARD e COMPANION continuam
gerando seus acessórios automaticamente em TOKENS, com quantidade 1 e
`isAutoGenerated: true`. Para incluir um acessório manual, use `oracleId` no upsert.

A busca por nome tenta primeiro `/cards/search?lang=en&name_exact={name}&limit=1`
no Card Manager. Se não houver resultado, consulta
`/cards/search?lang=en&name={name}&limit=100&offset={offset}`, percorre todas as
páginas e aceita somente cartas multiface cujo `card_faces[0].name` seja
exatamente o nome informado. Assim, `Esika, God of the Tree`, como exportado pelo
Arena, resolve para `Esika, God of the Tree // The Prismatic Bridge`, mantendo o
`oracle_id` e todos os metadados da carta completa. O nome completo com ` // `
continua aceito. Nomes parciais ou apenas do verso não são aceitos nesse fallback;
mais de um `oracle_id` com a mesma face frontal gera erro 422 por ambiguidade.

O resultado usa o cache exclusivo `cards_by_name`, cuja chave preserva maiúsculas
e minúsculas. O nome deve corresponder exatamente à grafia no Card Manager (por
exemplo, `Lightning Bolt`, não `lightning bolt`). Linhas com o nome frontal e o
nome completo da mesma carta somam quantidades por `oracle_id` e zona. As regras
existentes do formato são aplicadas; qualquer falha desfaz toda a importação,
incluindo o deck.
O deck permanece com status UNDEFINED até a análise.
Buscas válidas sem correspondência retornam 422 com o nome informado. Falhas HTTP,
de conexão ou respostas inválidas do Card Manager retornam 503.

Erros de parsing retornam `422 application/problem+json`, com `code` estável,
`field: "rawText"` e `line` quando há uma linha específica. `line` começa em 1 e
conta também cabeçalhos e linhas vazias do texto original. O frontend deve usar
esses campos para localizar/traduzir o erro, sem extrair dados de `detail`.

| Código | Significado |
| --- | --- |
| `IMPORT_TEXT_REQUIRED` | Texto nulo ou vazio ao chamar o parser. |
| `IMPORT_NO_CARDS` | Texto contém apenas cabeçalhos/linhas vazias. |
| `IMPORT_INVALID_LINE` | Linha não segue `quantidade nome` nem é um cabeçalho aceito. |
| `IMPORT_INVALID_QUANTITY` | Quantidade zero ou fora do intervalo de inteiros positivos. |
| `IMPORT_TOKENS_NOT_SUPPORTED` | Cabeçalho `Tokens` presente no texto, mesmo sem cartas nessa zona. |

Por exemplo, `Deck\n\nbad line` retorna `code: "IMPORT_INVALID_LINE"`,
`field: "rawText"`, `line: 3`, além dos campos padrão de `ProblemDetail`.
Erros gerais não incluem `line`. Texto vazio na requisição HTTP continua sendo
rejeitado antes do parser com `400` e `errors[]` de validação de campos.
Resolução de nomes e regras de composição mantêm seus contratos atuais;
essa informação de linha se aplica aos erros de sintaxe do texto.

### Criar deck

```http
POST /api/decks
```

```json
{
  "name": "Meu deck Modern",
  "format": "MODERN"
}
```

| Campo | Obrigatório | Regra |
| --- | --- | --- |
| `name` | Sim | Não vazio; até 255 caracteres. |
| `format` | Sim | Um dos cinco formatos suportados. |
| `commanderOracleIds` | Em COMMANDER | Um ou dois UUIDs distintos de comandantes compatíveis. Nos outros formatos, omita ou envie lista vazia. |

Exemplo com The Tenth Doctor e Rose Tyler:

```json
{
  "name": "Doctor + Rose",
  "format": "COMMANDER",
  "commanderOracleIds": [
    "23af0a0a-1d12-47c7-b191-2bd3f84eea93",
    "2f56de72-80d7-48c6-9b57-84fbd252c699"
  ]
}
```

Retorna `201 Created`, cabeçalho `Location` e o deck criado. Os comandantes são
incluídos na zona COMMANDER com quantidade 1, após validar seus metadados e regras.
O status inicial é UNDEFINED. O endereço de `Location` identifica o recurso;
pode ser consultado com `GET /api/decks/{deckId}`.

### Adicionar, atualizar ou remover uma carta

```http
PUT /api/decks/{deckId}/cards
```

```json
{
  "oracleId": "b2c6aa39-2d2a-459c-a555-fb48ba993373",
  "boardType": "MAINBOARD",
  "quantity": 60
}
```

Este exemplo adiciona 60 cópias de Island. A quantidade substitui o total da carta
na zona; não é um incremento. Envie `quantity: 0` para remover o registro.

- `oracleId`, `boardType` e `quantity` são obrigatórios.
- Quantidades negativas são rejeitadas.
- Um upsert bem-sucedido retorna `200 OK` com o deck e invalida a análise anterior:
  `status: UNDEFINED`, `analyzedAt: null` e `analysisMessages: []`.
- Essa invalidação também ocorre quando a requisição repete a quantidade existente.
- Deck inexistente ou pertencente a outro usuário retorna 404.

### Acessórios automáticos (TOKENS)

A inclusão de uma carta em MAINBOARD, COMMANDER, SIDEBOARD ou COMPANION resolve os IDs de impressão de
`all_parts` com `POST /cards/resolve`, corpo `{"ids": ["<scryfall UUID>"]}`.
A resposta deve conter todos os IDs solicitados e os campos `id`, `oracleId`,
`name`, `layout` e `typeLine`. São acessórios os layouts `token`,
`double_faced_token`, `emblem`, ou tipos contendo `Dungeon`.

Acessórios ausentes são inseridos em TOKENS com quantidade 1 e
`isAutoGenerated: true`. Um registro existente mantém sua quantidade e origem.
Alterar a quantidade de um token automático preserva a flag. Ao remover a última
carta que o referencia nessas quatro zonas, esse token é removido mesmo que
sua quantidade tenha sido editada. Tokens inseridos manualmente têm flag false e
nunca são removidos por esse processo. O cliente não controla a flag.

Atualizar uma quantidade positiva de uma carta geradora não recalcula acessórios.
SIDEBOARD e COMPANION também geram acessórios e mantêm referências. Criação com comandantes e
importação também geram acessórios. A importação rejeita o bloco `Tokens` para
evitar a seleção ambígua de acessórios por nome; registros manuais são incluídos
por `oracleId` no upsert. Texto puro continua exportando esse bloco; o formato
Arena o omite.

Os IDs são deduplicados e resolvidos em lotes de até 100, com cache por impressão
(limitado a 10.000 entradas, validade de 24 horas desde a resolução).
A limpeza consulta cada oracleId restante uma vez e compara conjuntos de referências.
Respostas incompletas ou inválidas e falhas remotas abortam a transação inteira;
não são interpretadas como ausência de referências. O lock por deck também cobre
a sincronização de acessórios. O cliente HTTP usa explicitamente HTTP/1.1, sem tentativa de upgrade h2c,
permitindo comunicação com o card-manager por HTTP na rede local.
Os timeouts HTTP padrão são 5s para conexão e 15s
para leitura; o tempo total pode ser maior quando várias consultas forem necessárias.

TOKENS fica fora das regras de legalidade, identidade de cor e limites de cópias.
Estatísticas e sugestões de mana continuam restritas a MAINBOARD e COMMANDER.
A migração V9 preserva registros existentes com flag false; decks já salvos não
recebem uma sincronização retroativa automática.

### Legalidades e limites de cópias

| Legalidade no formato | Comportamento |
| --- | --- |
| LEGAL | Aplica o limite do formato e os overrides. |
| RESTRICTED | Limita a uma cópia, mesmo que haja override. |
| BANNED ou NOT_LEGAL | Rejeita a inclusão. |
| Ausente ou desconhecida | Rejeita a inclusão por falta de legalidade confirmada. |

Formatos construídos usam limite base de quatro cópias; Commander usa uma. Terrenos
básicos e o texto `A deck can have any number of cards named` permitem quantidade
ilimitada. O texto `A deck can have up to ... cards named` define um limite específico.
As cópias são somadas entre as zonas, incluindo companion.

RESTRICTED é tratado pelo domínio, embora VINTAGE não seja um formato suportado.

### Comandantes e companion

`CommanderEligibilityRules.validate(CardDetailsResponse)` valida um comandante único
sem realizar consultas externas. Rejeita primeiro cartas banidas, não legais ou com
legalidade desconhecida, e depois exige uma criatura lendária ou permissão Oracle
explícita da própria carta (`<nome> can be your commander.`). Um Background isolado
não é aceito; sua seleção depende de Choose a Background.

Para `modal_dfc`, `transform`, `flip` e `adventure`, o validador usa o nome, tipo e
texto da primeira entrada de `card_faces`. Não combina características das faces
nem usa permissão ou Partner presentes somente no verso. As cartas de dupla-face
seguem a regra 712.8a; flip e adventure usam suas características iniciais/principais.
A identidade continua vindo de `color_identity` da carta inteira, incluindo as
faces aplicáveis (regra 903.4d). `null` significa identidade desconhecida e `[]`
significa identidade incolor confirmada.

Se um layout exigir faces e a face principal estiver ausente ou incompleta, a
criação retorna `COMMANDER_DATA_INCOMPLETE`. Layouts multiface sem interpretação
implementada também não são aprovados por uma combinação dos tipos da raiz. Na
análise de um deck existente, dados insuficientes são incerteza (`UNDEFINED`),
a menos que também exista uma violação confirmada, que produz `IRREGULAR`.


A aplicação reconhece pares com Partner, Partner with, Friends Forever,
Doctor's companion e Choose a Background, conforme as regras implementadas para
cada combinação. A identidade de cor do deck é a união das identidades dos
comandantes. As cartas precisam respeitar essa identidade.

**Doctor's companion e Companion são habilidades diferentes.** Doctor e seu
Doctor's companion ocupam COMMANDER: são dois comandantes e deixam 98 cartas na
mainboard. Uma carta com a palavra-chave `Companion`, como Keruga, ocupa a zona
COMPANION quando escolhida como companheiro e fica fora das 100 cartas.

Um deck pode ter no máximo um companion, com quantidade 1. Em formatos construídos,
sideboard + companion não pode ultrapassar 15 cartas. Em Commander não há sideboard;
companion é a única zona externa permitida. Suas condições específicas de construção
ainda não são validadas no MVP.

## Análise de decks

```http
POST /api/decks/{deckId}/analysis
```

Exige autenticação e propriedade do deck; deck inexistente ou pertencente a outro
usuário retorna `404 Not Found`.

Não exige corpo. Reconsulta os metadados das cartas e persiste o resultado, os motivos
e a data. Retorna `200 OK` mesmo quando encontra um deck irregular.

| Formato | Verificações de tamanho |
| --- | --- |
| STANDARD, MODERN, PIONEER e LEGACY | Mainboard com pelo menos 60 cartas; sideboard + companion com no máximo 15; sem comandantes. |
| COMMANDER | Exatamente 100 cartas entre mainboard e comandantes; um ou dois comandantes válidos; nenhum sideboard. Companion fica fora das 100. |

Além do tamanho, revalida legalidades, limites de cópias, overrides, compatibilidade
dos comandantes, identidade de cor e requisitos básicos da zona COMPANION.

| Status | Significado |
| --- | --- |
| REGULAR | Todas as verificações implementadas passaram e não há incerteza identificada. |
| IRREGULAR | Existe uma violação confirmada. Tem prioridade sobre incertezas. |
| UNDEFINED | Sem análise atual, ou com companion/metadata indisponível/legalidade desconhecida, sem violação já confirmada. |

Exemplo ilustrativo da estrutura da resposta para um deck Modern vazio analisado:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "Meu deck Modern",
  "format": "MODERN",
  "createdAt": "2026-09-16T12:00:00Z",
  "updatedAt": "2026-09-16T12:01:00Z",
  "cards": [],
  "status": "IRREGULAR",
  "analyzedAt": "2026-09-16T12:01:00Z",
  "analysisMessages": [
    "Constructed mainboard requires at least 60 cards."
  ]
}
```

Cada item de `cards` contém `id`, `oracleId`, `boardType`, `quantity` e `isAutoGenerated`. Decks com
companion recebem uma explicação sobre a ausência de validação de suas condições
específicas. Falhas de consulta durante a análise são registradas como incerteza;
não são tratadas como prova de que a carta foi banida.

A resposta também inclui `analysisReasons`, uma lista ordenada de motivos estruturados.
Cada item contém `code` (código estável), `severity` (`VIOLATION` ou `UNCERTAINTY`),
`message` (texto em inglês) e `parameters` (objeto JSON). `analysisMessages` continua
presente e contém exatamente os textos de `analysisReasons`, na mesma ordem, com
violações antes das incertezas. Ambos os campos são persistidos como uma única fonte
de dados e ficam vazios quando a composição invalida a análise ou uma nova análise
não encontra motivos. O detalhe do deck retorna os mesmos motivos salvos.

Exemplo para um mainboard de 59 cartas:

```json
{
  "code": "MAINBOARD_SIZE_BELOW_MINIMUM",
  "severity": "VIOLATION",
  "message": "Constructed mainboard requires at least 60 cards.",
  "parameters": { "actual": 59, "minimum": 60 }
}
```

O frontend pode usar códigos e parâmetros para apresentação localizada sem comparar
frases. UUIDs são strings, quantidades são números e coleções são arrays JSON.

| Código | Parâmetros |
| --- | --- |
| `MAINBOARD_SIZE_BELOW_MINIMUM` | `actual`, `minimum` |
| `COMMANDER_DECK_SIZE_INVALID` | `actual`, `required` |
| `SIDEBOARD_SIZE_LIMIT_EXCEEDED` | `actual`, `maximum` |
| `BOARD_TYPE_NOT_SUPPORTED` | `boardType`, `format` |
| `INVALID_COMMANDER_SELECTION` | `oracleIds`; `quantities` quando a seleção/quantidade é inválida |
| `INVALID_COMPANION_SELECTION` | `oracleIds`, `quantities` |
| `INVALID_CARD_QUANTITY` | `oracleId`, `actual` |
| `COPY_LIMIT_EXCEEDED` | `oracleId`, `actual`, `maximum` |
| `CARD_BANNED`, `CARD_NOT_LEGAL` | `oracleId`, `cardName`, `format`, `legality` |
| `CARD_METADATA_UNAVAILABLE`, `CARD_METADATA_INCONSISTENT` | `oracleId` |
| `CARD_LEGALITY_UNKNOWN` | `oracleId`, `format` |
| `COLOR_IDENTITY_UNKNOWN` | `oracleId` |
| `COLOR_IDENTITY_INCOMPATIBLE` | `oracleId`, `colorIdentity`, `commanderColorIdentity` |
| `COMPANION_NOT_ELIGIBLE` | `oracleId` |
| `COMPANION_REQUIREMENTS_NOT_EVALUATED` | `oracleIds` |
| `COMMANDER_NOT_ELIGIBLE`, `INCOMPATIBLE_COMMANDER_PAIR`, `COMMANDER_DATA_INCOMPLETE` | `oracleIds` |
| `LEGACY_MESSAGE` | Objeto vazio; `severity: LEGACY` |

Análises anteriores à migração V12 mantêm os textos originais com `LEGACY_MESSAGE`
e `severity: LEGACY`: não se deduz código ou severidade individual pelo texto.
Uma nova análise substitui esses motivos pelos códigos atuais. Clientes devem ter
um fallback para códigos desconhecidos, exibindo `message`.

A análise é uma fotografia dos dados disponíveis naquele momento. Alterações de
legalidade no catálogo exigem uma nova chamada; não existe reanálise agendada.
O status REGULAR não representa certificação oficial de torneio nem implementação
integral de todas as regras de Magic.

### Respostas de erro

As mensagens de erro da API são em inglês. As validações de entrada usam
mensagens explícitas em inglês, inclusive quando a requisição informa
`Accept-Language: pt-BR`. O frontend pode traduzir os códigos de erro para
o idioma da interface.

Erros de domínio e validação dos endpoints de decks utilizam Problem Details,
com `Content-Type: application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "The two commanders do not have compatible partner abilities",
  "instance": "/api/decks",
  "code": "INCOMPATIBLE_COMMANDER_PAIR",
  "field": "commanderOracleIds",
  "oracleIds": [
    "00000000-0000-0000-0000-000000000001",
    "00000000-0000-0000-0000-000000000002"
  ]
}
```

| Status | Motivo |
| --- | --- |
| 400 Bad Request | Corpo, tipo ou campos inválidos; erros de Bean Validation incluem `errors` com campo e mensagem. |
| 404 Not Found | Deck inexistente/não pertencente ao usuário ou carta não encontrada por oracle ID. Na importação por nome, carta não encontrada retorna 422. |
| 422 Unprocessable Entity | Violação de regra ao criar ou editar o deck. |
| 503 Service Unavailable | Card Manager indisponível durante criação ou edição. |

As rejeições de regras listadas abaixo expõem códigos estáveis. `detail` continua
sendo uma mensagem técnica; o frontend pode traduzir `code` e destacar `oracleIds`.
`field` é incluído quando há um campo aplicável (`commanderOracleIds`, `oracleId`,
`quantity` ou `boardType`).
Os metadados são extensões opcionais: outras regras existentes podem retornar apenas
o Problem Details básico.

| Código | Significado |
| --- | --- |
| `INVALID_COMMANDER_SELECTION` | Seleção ausente, duplicada, com IDs nulos, excessiva, com quantidade diferente de 1 por comandante ou incompatível com o formato. |
| `COMMANDER_NOT_ELIGIBLE` | Carta não pode ser o comandante selecionado. |
| `INCOMPATIBLE_COMMANDER_PAIR` | Cartas elegíveis, mas sem combinação compatível. |
| `CARD_BANNED` | Carta banida no formato. |
| `CARD_NOT_LEGAL` | Carta não legal no formato. |
| `CARD_LEGALITY_UNKNOWN` | Legalidade ausente ou desconhecida. |
| `COMMANDER_DATA_INCOMPLETE` | Características necessárias à elegibilidade não confirmadas. |
| `COLOR_IDENTITY_UNKNOWN` | Identidade de cor não confirmada. |
| `COPY_LIMIT_EXCEEDED` | Quantidade excede o limite de cópias da carta no formato, somando as zonas aplicáveis. |
| `COLOR_IDENTITY_INCOMPATIBLE` | Identidade de cor da carta incompatível com a identidade dos comandantes. |
| `COMMANDER_SIZE_LIMIT_EXCEEDED` | Mainboard e comandantes ultrapassam o total de 100 cartas. |
| `SIDEBOARD_SIZE_LIMIT_EXCEEDED` | Sideboard e companion ultrapassam juntos 15 cartas em formato construído. |
| `INVALID_COMPANION_QUANTITY` | Quantidade do companion diferente de 1. |
| `COMPANION_NOT_ELIGIBLE` | Carta sem a habilidade Companion na zona companion. |
| `COMPANION_LIMIT_EXCEEDED` | Mais de um companion no deck. |
| `LAST_COMMANDER_REQUIRED` | Remoção do último comandante enquanto existem cartas no mainboard ou companion. |
| `BOARD_TYPE_NOT_SUPPORTED` | Sideboard em Commander ou comandante em formato construído durante inclusão/importação de cartas. |
| `CARD_NOT_ACCESSORY` | Carta não classificada como acessório na zona TOKENS. |

Limites de cópias/tamanho, identidade incompatível e rejeições de zona/companion
retornam `detail` seguro em inglês, sem interpolar
nomes ou textos recebidos do catálogo. Limites de cópias e tamanho indicam
`field: "quantity"`; identidade incompatível indica `field: "oracleId"`.
`oracleIds` identifica a carta afetada. Uma inclusão ou atualização rejeitada
preserva as quantidades salvas e a análise anterior do deck. O frontend deve
manter a quantidade confirmada pelo servidor ao receber 422.

O limite conjunto de sideboard/companion, a quantidade inválida de companion e a
remoção do último comandante indicam `field: "quantity"` e o oracle ID da carta
da operação. Companion inelegível e carta não acessória indicam `field: "oracleId"`.
Zona incompatível indica `field: "boardType"`. Excesso de companions também indica
`boardType`, com os IDs do companion existente e do candidato em `oracleIds`.
Seleção inválida de comandantes durante edição indica `quantity` quando a quantidade
do candidato é diferente de 1; nos demais casos indica `commanderOracleIds`, com os
IDs dos comandantes selecionados. Se não houver comandante, essa lista vazia é omitida.
As mesmas regras e códigos se aplicam à importação; para um conjunto de companions
já inválido, os IDs indicam as cartas que violam o limite ou a quantidade.

Na validação de entrada (400), cada item de `errors` contém `field`, `message` e
`code`: `INVALID_COMMANDER_SELECTION` para `commanderOracleIds` e seus elementos;
`INVALID_FIELD` para outros campos. JSON malformado mantém o retorno 400 genérico.
O serviço também protege a seleção em chamadas internas, usando o código de seleção
com 422. Não é preciso comparar mensagens em inglês para esses motivos.

Os fluxos de autenticação também possuem tratamentos próprios de erro; nem todas as
respostas da aplicação usam o mesmo envelope. O formato do deck não pode ser alterado.

## Desenvolvimento local

Disponibilize Java 25, PostgreSQL, Docker para Testcontainers e os serviços Card
Manager/SMTP necessários aos fluxos que serão exercitados.

O profile `dev` usa PostgreSQL em `localhost:5432/postgres`, usuário e senha
`postgres`, Card Manager em `localhost:8000` e SMTP em `localhost:1025`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Recursos disponíveis em dev:

- API: `http://localhost:8080/api`
- Swagger UI: `http://localhost:8080/api/swagger-ui/index.html`
- OpenAPI: `http://localhost:8080/api/v3/api-docs`

Para uma conexão JDBC diferente:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mtg_deck_builder \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### Testes e build

```bash
./mvnw test
./mvnw package
```

A suíte combina testes unitários, WebMvcTest, MockRestServiceServer e testes de
persistência com PostgreSQL via Testcontainers. O Docker precisa estar acessível;
os testes PostgreSQL não são substituídos pelo H2 do profile `test`. Os testes de
integração HTTP simulam o Card Manager e não dependem de um catálogo real.

O build do Dockerfile pula os testes; execute a suíte separadamente antes de validar
uma entrega. Não use as credenciais e o segredo JWT de desenvolvimento fora desse
ambiente.

## Créditos e aviso legal

Este projeto utiliza o **MTG Card Manager** como serviço de catálogo. Os dados de
cartas consultados por essa integração têm origem no [Scryfall](https://scryfall.com/)
e em seu [Bulk Data](https://scryfall.com/docs/api/bulk-data). Agradecemos ao Scryfall
por disponibilizar essa infraestrutura à comunidade. O Deck Builder não é afiliado,
patrocinado ou endossado pelo Scryfall, e não realiza a importação direta do catálogo.

Magic: The Gathering, nomes de cartas, textos, símbolos, imagens e demais materiais
relacionados pertencem a seus respectivos titulares, incluindo a Wizards of the
Coast e, quando aplicável, titulares de propriedades intelectuais licenciadas.

**MTG Deck Builder é um projeto de fãs não oficial, sem aprovação, patrocínio ou
endosso da Wizards of the Coast.** Parte dos materiais referenciados pertence à
Wizards of the Coast. © Wizards of the Coast LLC.

A finalidade do software é auxiliar a organização, construção e análise técnica de
decks. Ele não foi criado para fabricar cartas falsificadas ou proxies, redistribuir
obras protegidas sem autorização, substituir produtos oficiais ou facilitar pirataria.
O acesso aos dados por meio de uma API não transfere direitos sobre os materiais.

Quem operar, publicar ou distribuir uma instalação deve respeitar os direitos,
licenças e termos aplicáveis. Consulte a
[Política de Conteúdo de Fãs da Wizards of the Coast](https://company.wizards.com/pt-BR/legal/fancontentpolicy)
antes de publicar ou explorar o projeto; este aviso não constitui declaração de
aprovação pela Wizards nem substitui o cumprimento dessa política.

Os créditos e avisos sobre dados e marcas não concedem uma licença para o código-fonte.
O repositório ainda não contém um arquivo LICENSE que defina sua licença de distribuição.

### Verificação da criação de Commander

As fixtures em `src/test/resources/cards` foram capturadas do Card Manager local
em 2026-10-06 e reduzidas aos campos de regras. Incluem Isamaru, Esika, Jace e
Invasion of Ikoria. Esta última reproduz o erro anterior: criatura lendária somente
no verso, com `type_line` combinado na raiz. Os testes comuns são independentes do
catálogo em execução.

`CommanderCreationTransactionTest` verifica persistência real e ausência de registros
após falha, usando o banco do perfil de teste. Para um teste adicional com o Card
Manager real, use **um banco descartável** e execute:

```bash
COMMANDER_LIVE_TEST_URL=http://localhost:8002 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/commander_tests \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
SPRING_DATASOURCE_USERNAME=commander_test \
SPRING_DATASOURCE_PASSWORD=commander_test \
./mvnw -Dtest=CommanderLiveIntegrationTest test
```

Sem `COMMANDER_LIVE_TEST_URL`, o teste vivo é ignorado. Ele consulta Esika,
Isamaru e Invasion of Ikoria, verifica rejeições sem persistência e cria/remove
somente o próprio deck de teste. Os casos de identidade desconhecida usam fixtures
nos testes transacionais para não modificar o catálogo real.
