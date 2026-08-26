# ChangeLens

Plug-in Eclipse leggero che porta negli editor di testo due elementi in stile IntelliJ:

- **barre delle modifiche** in una colonna dedicata a sinistra del testo, subito accanto al codice;
- **autore della dichiarazione** in coda alla riga del metodo, con icona, `+N` e `*`.

La barra panoramica di destra resta **quella di Eclipse**, con errori, warning, task e segnalibri e nient'altro: ChangeLens non ci contribuisce annotazioni, ci aggiunge solo la linguetta di scorrimento semitrasparente.

## Cosa disegna

| Situazione | Indicatore |
| --- | --- |
| Riga committata e intatta | niente |
| Righe aggiunte | barra verde continua, alta esattamente quanto il blocco |
| Righe modificate | barra arancione continua, alta esattamente quanto il blocco |
| Righe eliminate | stanghetta rossa sul confine fra le due righe rimaste |

Le barre non sono per riga: un blocco contiguo di modifiche produce **una sola** barra stondata, alta quanto il blocco.

Le barre sono ancorate al codice tramite Position registrate nel documento: scorrendo non salgono ne scendono, e digitando non spariscono per poi ricomparire.

Passando il mouse su una barra a sinistra, o su una stanghetta della barra panoramica a destra, si apre un fumetto largo con il codice di quel punto e i numeri di riga. Allontanando il mouse si chiude.

## Autore

Formato: `Nome`, `Nome+N` per gli altri autori che hanno toccato il corpo, `*` se il metodo ha modifiche non ancora committate.

- metodo nuovo ma sintatticamente integro: nome configurato in `user.name` seguito da `*`;
- dichiarazione non associabile a HEAD con sicurezza - corpo non chiuso, graffa cancellata, blame senza risposta: `new*`. In quel caso non si tira a indovinare un autore.

L'etichetta e cliccabile: si sottolinea al passaggio del mouse, il cursore diventa una mano e il clic apre le **Revisions** di Eclipse con `Color by Author` e `Show Author`. Un secondo clic le chiude. Con la modalita privacy il nome si riduce alle iniziali, mantenendo `+N` e `*`.

## Stabilita

La versione precedente disegnava tutto con un solo `PaintListener` sopra la `StyledText`, chiedendo ridisegni dall'interno del disegno stesso: da li lo `StackOverflowError`, le barre fuori posto e la barra destra sovrapposta al codice. Ora:

- le barre stanno in un `IVerticalRulerColumn` vero, quindi le coordinate delle righe le calcola Eclipse e sono esatte anche con folding, word wrap e righe di altezza diversa;
- la barra destra e quella nativa di Eclipse, alimentata via modello annotazioni: **nessuna** superficie disegnata sopra il codice, quindi nessuno spazio rubato a destra;
- il ridisegno della colonna e sincrono sugli eventi di scorrimento (`redraw()` + `update()`, come le colonne native): senza update() il disegno arriva un fotogramma dopo il testo, ed e quello che si vedeva come barra che scivola;
- il riconoscimento delle dichiarazioni e una scansione lineare, non una regex: il backtracking del motore regex e ricorsivo e su righe lunghe arriva a `StackOverflowError`;
- un solo `LensController` per editor possiede lo stato, con contatore di generazione, debounce e Job cancellabili; i ridisegni sono coalescati una volta per ciclo di eventi, quindi nessuna catena puo rientrare su se stessa;
- il disegno dei nomi autore e protetto da una guardia e si auto-disattiva dopo pochi errori invece di far cadere il loop degli eventi;
- i colori sono condivisi per Display e liberati una sola volta: nessun handle SWT orfano.

Quick Diff nativo **non** viene piu toccato di default: e l'unica cosa che ChangeLens faceva sulle colonne di Eclipse, e nessun indicatore vale il rischio di interferire con errori e warning. Resta attivabile dalle preferenze e viene comunque ripristinato alla chiusura.

## Barra di scorrimento

La barra verticale di sistema viene nascosta e sostituita da una linguetta stondata disegnata sulla stessa striscia dei segnaposto: dietro resta lo sfondo dell'editor, senza binario ne frecce, e modifiche, errori e warning convivono con la linguetta invece di occupare due colonne. Si trascina come una scrollbar normale e si disattiva dalle preferenze; allo smontaggio la barra nativa torna com'era.

## Requisiti

- Eclipse 3.6 o successivo, Java 8 (bytecode 52);
- EGit/JGit installati, file dentro un repository Git.

Solo EGit/JGit, nessun processo esterno, nessun supporto SVN. Diff, analisi e blame girano in Job in background; il blame usa `BlameResult.computeRange` solo sui metodi effettivamente visibili.

## Importazione e prova in Eclipse

1. `File > Import > Existing Projects into Workspace` e selezionare questa cartella.
2. `Run As > Eclipse Application`.
3. Aprire un file di un repository Git.
4. Configurare da `Preferences > General > Editors > Text Editors > ChangeLens`.
   I colori nella barra destra si regolano da `Text Editors > Annotations` (voci `ChangeLens: ...`).

## Limiti

Il riconoscimento delle dichiarazioni e volutamente generico e copre Java, JavaScript/TypeScript e sintassi simili. Il confronto avviene sul testo in memoria contro `HEAD`, ignorando differenze di fine riga e spazi finali.
