package main;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * PuzzleEngine: Motor de geração e resolução de puzzles Slitherlink (Loopy).
 * 
 * A arquitetura utiliza uma abordagem híbrida:
 * 1. Geração Probabilística: Crescimento de loop baseado em pesos de vizinhança.
 * 2. Dedução Lógica (Analyzer): Motor de propagação de restrições via bitmasks.
 * 3. Busca Exaustiva (Backtracking): Verificador de unicidade com podas (pruning) de
 *    Forward Checking e validação de conectividade global via DSU.
 */
public class PuzzleEngine {
    public enum Dificuldade { EASY, NORMAL, HARD, EXPERT }

    static final int MAXS = 100; // Aumentado para suportar puzzles maiores (como 34x13)
    static final int PESO_1 = 3;
    static final int PESO_2 = 5;
    static final int PESO_3T = 5;
    static final int PESO_3Q = 2;
    static final int PESO_4 = 2;
    static final int PESO_5R = 1;
    static final int PESO_5E = 5;
    static final int PESO_6 = 1;
    static final int PESO_7 = 1;

    // Máscaras de bits representando as 8 células adjacentes (vizinhança de Moore).
    // Utilizado pelo algoritmo de crescimento para garantir formas orgânicas.
	static final int[] vizinhancasValidas = {
		1, 2, 4, 8, 16, 32, 64, 128,
		3, 6, 12, 24, 48, 96, 192, 129, 
		7, 14, 28, 56, 112, 224, 193, 131, 
		15, 30, 60, 120, 240, 225, 195, 135, 
		31, 62, 124, 248, 241, 227, 199, 143, 
		63, 126, 252, 249, 243, 231, 207, 159, 
		127, 254, 253, 251, 247, 239, 223, 191
	};
	
	static final int[] pesosValidos = {
		PESO_1, 0, PESO_1, 0, PESO_1, 0, PESO_1, 0, // Pesos para vizinhanças de 1 bit
		PESO_2, PESO_2, PESO_2, PESO_2, PESO_2, PESO_2, PESO_2, PESO_2,
		PESO_3Q, PESO_3T, PESO_3Q, PESO_3T, PESO_3Q, PESO_3T, PESO_3Q, PESO_3T,
		PESO_4, PESO_4, PESO_4, PESO_4, PESO_4, PESO_4, PESO_4, PESO_4,
		PESO_5R, PESO_5E, PESO_5R, PESO_5E, PESO_5R, PESO_5E, PESO_5R, PESO_5E,
		PESO_6, PESO_6, PESO_6, PESO_6, PESO_6, PESO_6, PESO_6, PESO_6,
		0, PESO_7, 0, PESO_7, 0, PESO_7, 0, PESO_7 

	};
	
	static final int[] dc = {0, 1, 1, 1, 0, -1, -1, -1};
	static final int[] dl = {-1, -1, 0, 1, 1, 1, 0, -1};

	int linhas, colunas, lastSeed;
	Dificuldade lastNivel;
    
    // Matriz de estado: 8 bits inferiores para vizinhos ativos, bit 9 (256) indica se a célula é ativa.
	int vizinhancas[][] = new int [MAXS + 2][MAXS + 2];
	int[] pesos;
	int[][] puzzle;
	String strRelatorio;
	SquareBoard sb = new SquareBoard();
	private final AtomicLong totalNodes = new AtomicLong(0);

    /**
     * ThreadLocal garante isolamento de estado para SquareBoard durante a redução paralela (Nível EXPERT).
     * Evita alocações custosas de objetos de grafo em cada tentativa de remoção de pista.
     */
	private static final ThreadLocal<SquareBoard> threadLocalSb = ThreadLocal.withInitial(SquareBoard::new);
	private static final ConcurrentHashMap<PuzzleKey, Integer> solutionCache = new ConcurrentHashMap<>();
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);
	
	public PuzzleEngine() {
		pesos = new int[512];
		for(int i = 0; i < vizinhancasValidas.length; i++)
			pesos[vizinhancasValidas[i]] = pesosValidos[i];		
	}
	
    /**
     * Ativa uma célula e propaga a influência para os vizinhos.
     * Este método define a topologia do loop inicial que será transformado em pistas numéricas.
     */
	void ativaCasa(int l, int c) {
		vizinhancas[l][c] |= 256;
		for(int i = 0; i < 8; i++)
			vizinhancas[l - dl[i]][c - dc[i]] |= 1 << i;		
	}
	
	public int[][] criarPuzzle(int l, int c, Dificuldade nivel) {
		Random rnd = new Random();
		return criarPuzzle(l, c, nivel, rnd.nextInt());
	}
	
	public int[][] criarPuzzle(int l, int c, Dificuldade nivel, int seed) {
		int i, j, k, n, p;
		int[] prob = new int[l * c];
		linhas = l;
		colunas = c;
		this.lastSeed = seed;
		this.lastNivel = nivel;
		
		solutionCache.clear(); 

		for(i = 0; i <= l + 1; i++)
			for(j = 0; j <= c + 1; j++)
				vizinhancas[i][j] = 0;
		Random rnd = new Random(seed);
		ativaCasa(l / 2 + rnd.nextInt(2), c / 2 + rnd.nextInt(2));
		n = (rnd.nextInt(l * c * 10) + l * c * 60) / 100;
		while(n-- > 0) {
			p = 0;
			k = 0; 
			for(i = 1; i <= l; i++)
				for(j = 1; j <= c; j++)
					prob[k++] = p += pesos[vizinhancas[i][j]];
			if(p == 0)
				break;
			p = rnd.nextInt(p);
			k = 0;
			while(prob[k] <= p)
				k++;
			i = k / c + 1;
			j = k % c + 1;
			ativaCasa(i, j);
		}
		puzzle = new int[l][c];
		for(i = 0; i < l; i++)
			for(j = 0; j < c; j++) {
				n = vizinhancas[i + 1][j + 1];
				p = 0;
				if((n & 256) > 0)
					for(k = 0; k < 8; k += 2) {
						if(((1 << k) & n) == 0) 
							p++;
					}
				else
					for(k = 0; k < 8; k += 2) {
						if(((1 << k) & n) != 0)
							p++;						
					} 
				puzzle[i][j] = p;
			}
		if(nivel != Dificuldade.EASY) {
            // O processo de "Escultura" (Reduction):
            // Tenta remover pistas aleatoriamente. Uma pista só é removida se o puzzle
            // continuar tendo uma solução única. Nível EXPERT realiza múltiplas passagens
            // paralelas para encontrar o conjunto mínimo de pistas.
			for(i = 0; i < prob.length; i++)
				prob[i] = i;
			
			final int[][] puzzleOriginal = new int[l][c];
			for (int rIdx = 0; rIdx < l; rIdx++) puzzleOriginal[rIdx] = puzzle[rIdx].clone(); 
			
			int iteracoes = (nivel == Dificuldade.EXPERT) ? 3 : 1;
			final int[] probOriginal = prob;
			
			puzzle = IntStream.range(0, iteracoes)
				.parallel()
				.mapToObj(it -> executarReducao(puzzleOriginal, probOriginal.clone(), nivel, seed + (it * 100)))
				.min(Comparator.comparingInt(this::contarPistas))
				.orElse(puzzleOriginal); 
		}

		sb = threadLocalSb.get(); 
		sb.init(puzzle); 
		Analyzer.analyze(); 

		return puzzle.clone();
	}

	private int[][] executarReducao(int[][] original, int[] probOrder, Dificuldade nivel, int seed) {
		int l = original.length;
		int c = original[0].length;
		int[][] puzzleAtual = new int[l][c];
		for (int i = 0; i < l; i++) puzzleAtual[i] = original[i].clone();
		
		Random rnd = new Random(seed);
		// Embaralha a ordem de tentativa de remoção para esta iteração
		for (int i = probOrder.length - 1; i >= 0; i--) {
			int j = rnd.nextInt(i + 1);
			int p = probOrder[j];
			probOrder[j] = probOrder[i];
			probOrder[i] = p;
		}

		SquareBoard localSb = threadLocalSb.get(); 
		for (int i = 0; i < probOrder.length; i++) {
			int rr = probOrder[i] / c;
			int cc = probOrder[i] % c;
			int k = puzzleAtual[rr][cc];
			if (k == -1) continue;
			puzzleAtual[rr][cc] = -1;

			localSb.init(puzzleAtual); // Reaproveita a estrutura de objetos, apenas limpa estados.
			Analyzer.analyze();
			boolean solvedByLogic = localSb.isSolved();

			if (nivel == Dificuldade.NORMAL) {
				if (!solvedByLogic) puzzleAtual[rr][cc] = k;
			} else {
				if (!solvedByLogic) { // Se a lógica dedutiva falhar, recorre ao backtracking pesado.
					int result = countSolutions(puzzleAtual);
					if (result != 1) puzzleAtual[rr][cc] = k;
				}
			}
		}
		return puzzleAtual;
	}

	void printLoop() {
		int i, j;
		for(i = 1; i <= linhas; i++) {
			for(j = 1; j <= colunas; j++)
				System.out.print(vizinhancas[i][j] >= 256 ? "#" : '.');
			System.out.println();
		}
		System.out.println();
	}

	void printPuzzle() {
		int i, j;
		for(i = 0; i < linhas; i++) {
			for(j = 0; j < colunas; j++)
				System.out.print(puzzle[i][j] >= 0 ? "" + puzzle[i][j] : ".");
			System.out.println();
		}
		System.out.println();
	}
    
	public boolean[][] testarSolucao(boolean[][] h, boolean[][] v) {
		boolean[][] OK = new boolean[linhas][colunas];
		boolean erroEncontrado = false;
		int i, j, d;
		for(i = 0; i < linhas; i++)
			for(j = 0; j < colunas; j++) {
				d = 0;
				if(h[i][j])
					d++;
				if(v[i][j])
					d++;
				if(h[i + 1][j])
					d++;
				if(v[i][j + 1])
					d++;
				if(puzzle[i][j] >= 0 && d != puzzle[i][j]) {
					erroEncontrado = true;
					OK[i][j] = false;
				}
				else
					OK[i][j] = true;
			}
		if(erroEncontrado) {
			strRelatorio = "Há casas com número errado de linhas."; // Mensagem de erro para pistas
			return OK;
		}
		
		// Verificação de Conectividade com DSU (Garante loop único)
		DSU dsu = new DSU((linhas + 1) * (colunas + 1));
		int arestasAtivas = 0;
		int primeiroVertice = -1;
		
		for(i = 0; i <= linhas; i++) {
			for(j = 0; j < colunas; j++) {
				if(h[i][j]) {
					dsu.union(i * (colunas + 1) + j, i * (colunas + 1) + (j + 1));
					arestasAtivas++;
					if(primeiroVertice == -1) primeiroVertice = i * (colunas + 1) + j;
				}
			} // Processa arestas horizontais
		}
		for(i = 0; i < linhas; i++) {
			for(j = 0; j <= colunas; j++) {
				if(v[i][j]) {
					dsu.union(i * (colunas + 1) + j, (i + 1) * (colunas + 1) + j);
					arestasAtivas++;
					if(primeiroVertice == -1) primeiroVertice = i * (colunas + 1) + j;
				}
			} // Processa arestas verticais
		}

		if(arestasAtivas > 0) {
			int root = dsu.find(primeiroVertice);
			for(i = 0; i <= linhas; i++) {
				for(j = 0; j <= colunas; j++) {
					int vIdx = i * (colunas + 1) + j;
					if(dsu.degree[vIdx] > 0 && dsu.find(vIdx) != root) { // Verifica se todos os vértices conectados pertencem ao mesmo componente
						strRelatorio = "A solução contém múltiplos loops isolados.";
						for(int k=0; k<linhas; k++) Arrays.fill(OK[k], false);
						return OK;
					}
				}
			}
		}

		// Testa o grau de cada vértice. Deve ser 0 ou 2.
		// Nota: os puzzles gerados por este programa contêm pistas que
		// forçam a presença de um único loop. Respostas que satisfazem
		// as pistas e têm apenas vértices de grau 0 ou 2 só podem conter 
		// um loop. Caso o processo de geração de puzzles seja modificado,
		// este método terá de ser modificado para testar a presença de
		// mais loops. (Este comentário é um lembrete para futuras modificações)
		for(i = 0; i <= linhas; i++) {
			for(j = 0; j <= colunas; j++) {
				d = 0;
				if(i > 0 && v[i - 1][j])
					d++;
				if(i < linhas && v[i][j])
					d++;
				if(j > 0 && h[i][j - 1])
					d++;
				if(j < colunas && h[i][j])
					d++;
				if(d != 0 && d != 2) {
					erroEncontrado = true;
					if(i > 0 && j > 0)
						OK[i - 1][j - 1] = false;
					if(i > 0 && j < colunas)
						OK[i - 1][j] = false;
					if(i < linhas && j > 0)
						OK[i][j - 1] = false;
					if(i < linhas && j < colunas)
						OK[i][j] = false;					
				}
			}
		}
		if(!erroEncontrado) {
			strRelatorio = "Correto!";
			return null;
		}
		else {
			strRelatorio = "Há ponto(s) com número errado de linhas.";
			return OK;
		}
	}

	/**
	 * Ponto de entrada para validação de unicidade com Memoization.
     * O Slitherlink é NP-Completo; o cache evita re-explorar estados idênticos 
     * gerados por diferentes ordens de remoção de pistas.
	 * @param puz Uma matriz 2D de inteiros representando o puzzle, com -1 para pistas removidas.
	 * @return O número de soluções encontradas (0, 1 ou >1). Retorna 1 para solução única.
	 */
	public int countSolutions(int[][] puz) {
		PuzzleKey lookupKey = new PuzzleKey(puz, false);
		Integer cached = solutionCache.get(lookupKey);
		if (cached != null) { cacheHits.incrementAndGet(); return cached; }
		cacheMisses.incrementAndGet();

		// Usa o SquareBoard do ThreadLocal para evitar alocações excessivas e re-linking
		SquareBoard localSb = threadLocalSb.get();
		localSb.init(puz);
		int result = countSolutionsInternal(puz, localSb);
		
		solutionCache.put(new PuzzleKey(puz, true), result);
		return result;
	}

	private int countSolutionsInternal(int[][] puz, SquareBoard localSb) {
		Analyzer.analyze(); // Executa o Analyzer para deduzir o máximo possível
		
		boolean[][] h = new boolean[linhas + 1][colunas];
		boolean[][] v = new boolean[linhas][colunas + 1];
		totalNodes.set(0);
        
        // DSU é inicializado para rastrear componentes conexos de vértices.
		DSU dsu = new DSU((linhas + 1) * (colunas + 1)); 
		return backtrack(puz, h, v, 0, 0, true, localSb, dsu);
	}

	private int backtrack(int[][] puz, boolean[][] h, boolean[][] v, int r, int c, boolean isH, SquareBoard localSb, DSU dsu) { // DSU adicionado
		totalNodes.incrementAndGet();
        
        // Máquina de estados da recursão: Processa todas as Horizontais, depois todas as Verticais.
		if (isH && c == colunas) return backtrack(puz, h, v, r + 1, 0, true, localSb, dsu);
		if (r > linhas) return backtrack(puz, h, v, 0, 0, false, localSb, dsu);
		if (!isH && c > colunas) return backtrack(puz, h, v, r + 1, 0, false, localSb, dsu);
		if (!isH && r == linhas) return testarSolucao(h, v) == null ? 1 : 0;
        
		// Poda por Dedução Lógica: Se o Analyzer já fixou a aresta, pula a ramificação (branching).
		int edgeState = isH ? localSb.he[r][c].state : localSb.ve[r][c].state;
		if (edgeState != EdgeState.unknown) {
			boolean val = (edgeState == EdgeState.present);
			if (isH) h[r][c] = val; else v[r][c] = val;
			
			int v1 = r * (colunas + 1) + c;
			int v2 = isH ? r * (colunas + 1) + (c + 1) : (r + 1) * (colunas + 1) + c;
			
			if (val) dsu.union(v1, v2); // Aplica a união
			if (isValidPartial(puz, h, v, r, c, isH, dsu)) { // Passa o DSU para validação parcial, incluindo detecção de ciclo
				int res = isH ? backtrack(puz, h, v, r, c + 1, true, localSb, dsu) : backtrack(puz, h, v, r, c + 1, false, localSb, dsu);
				if (val) dsu.rollback(); // Desfaz a união
				return res;
			}
			if (val) dsu.rollback();
			return 0; // Estado fixo viola restrições
		}

		int count = 0;
		boolean[] choices = {true, false};
		for (boolean choice : choices) {
			if (isH) h[r][c] = choice; else v[r][c] = choice;
			
			int v1 = r * (colunas + 1) + c;
			int v2 = isH ? r * (colunas + 1) + (c + 1) : (r + 1) * (colunas + 1) + c;
			
			if (choice) dsu.union(v1, v2); // Aplica a união
			if (isValidPartial(puz, h, v, r, c, isH, dsu)) { // Passa o DSU para validação parcial, incluindo detecção de ciclo
				if (isH) count += backtrack(puz, h, v, r, c + 1, true, localSb, dsu);
				else count += backtrack(puz, h, v, r, c + 1, false, localSb, dsu);
			}
			if (choice) dsu.rollback(); // Desfaz a união
			if (count > 1) break; 
		}
		if (isH) h[r][c] = false; else v[r][c] = false;
		return count;
	}

	/**
	 * Core de Pruning (Podas):
     * Verifica se a atribuição atual viola regras locais (pistas) ou globais (conectividade).
	 * 
     * O uso do DSU permite detectar "Sub-loops" prematuros em O(alpha(N)). 
     * Um ciclo só pode ser fechado se ele englobar todas as restrições do puzzle.
	 */
	private boolean isValidPartial(int[][] puz, boolean[][] h, boolean[][] v, int r, int c, boolean isH, DSU dsu) { // DSU adicionado
		// 1. Verificação local de grau de vértices (apenas os 2 vértices conectados à aresta)
		int vIdx1R = r, vIdx1C = c;
		int vIdx2R = isH ? r : r + 1, vIdx2C = isH ? c + 1 : c;
		
		if (!checkVertexViability(h, v, vIdx1R, vIdx1C, r, c, isH)) return false;
		if (!checkVertexViability(h, v, vIdx2R, vIdx2C, r, c, isH)) return false;
		// Como o union é chamado antes desta validação no backtrack, verificamos no histórico do DSU 
		// se a última operação foi um ciclo (v1 e v2 já estavam conectados).
		boolean cicloDetectado = ((isH && h[r][c]) || (!isH && v[r][c])) && dsu.history.peek() != null && dsu.history.peek()[0] == -1;
		if (cicloDetectado) {
			// Se o número de componentes é 1, significa que o loop fechado é o único componente.
			// Se o número de arestas presentes é menor que o total de arestas do puzzle, é um loop prematuro.
			if (dsu.numComponents > 1) { // Se ainda há componentes desconectados, este loop é prematuro
				return false;
			}
			// Se numComponents == 1, significa que este loop fechou o último componente.
			// Agora precisamos verificar se todas as pistas estão satisfeitas.
			// (A verificação de totalArestas == linhas * colunas é feita no testarSolucao final)
			
			// Verifica se todas as pistas são satisfeitas por este loop completo
			for (int i = 0; i < linhas; i++) {
				for (int j = 0; j < colunas; j++) {
					if (puz[i][j] != -1) {
						int d = (h[i][j] ? 1 : 0) + (h[i + 1][j] ? 1 : 0) + (v[i][j] ? 1 : 0) + (v[i][j + 1] ? 1 : 0);
						if (d != puz[i][j]) return false; // Pista não satisfeita
					}
				}
			}
		}

		int d1 = getDegree(h, v, vIdx1R, vIdx1C);
		int d2 = getDegree(h, v, vIdx2R, vIdx2C);

		// Regra do Vértice Concluído: No fluxo de varredura (Sweep-line), quando a última 
        // aresta possível de um vértice é processada, o grau DEVE ser 0 ou 2.
		if (!isH) {
			if (d1 != 0 && d1 != 2) return false;
			if (r == linhas - 1) {
				if (d2 != 0 && d2 != 2) return false;
			}
		}

		// 2. Otimização: Verifica apenas as 1 ou 2 células adjacentes à aresta atual
		if (isH) { // Aresta horizontal (r, c) afeta a célula acima e a de baixo
			// Aresta horizontal (r, c) afeta a célula acima e a de baixo
			if (r > 0 && !checkCellConstraints(puz, h, v, r - 1, c, r, c, isH)) return false;
			if (r < linhas && !checkCellConstraints(puz, h, v, r, c, r, c, isH)) return false;
		} else {
			// Aresta vertical (r, c) afeta a célula à esquerda e a da direita
			if (c > 0 && !checkCellConstraints(puz, h, v, r, c - 1, r, c, isH)) return false;
			if (c < colunas && !checkCellConstraints(puz, h, v, r, c, r, c, isH)) return false;
		}

		return true;
	}

	/**
	 * Avalia se um vértice ainda pode atingir um estado válido (grau 0 ou 2)
	 * com base nas arestas já decididas e as que ainda serão processadas (futuro).
	 * 
	 * @param h Arestas horizontais.
	 * @param v Arestas verticais.
	 * @param vr Linha do vértice.
	 * @param vc Coluna do vértice.
	 * @param curR Linha da aresta atual no backtrack.
	 * @param curC Coluna da aresta atual no backtrack.
	 * @param curIsH Tipo da aresta atual.
	 * @return true se o vértice ainda pode ser satisfeito (Forward Checking).
	 */
	private boolean checkVertexViability(boolean[][] h, boolean[][] v, int vr, int vc, int curR, int curC, boolean curIsH) {
		int currentDegree = 0;
		int potentialEdges = 0;

		if (vr > 0) {
			if (v[vr - 1][vc]) currentDegree++;
			else if (isEdgeFuture(vr - 1, vc, false, curR, curC, curIsH)) potentialEdges++;
		}
		if (vr < linhas) {
			if (v[vr][vc]) currentDegree++;
			else if (isEdgeFuture(vr, vc, false, curR, curC, curIsH)) potentialEdges++;
		}
		// Arestas Horizontais
		if (vc > 0) {
			if (h[vr][vc - 1]) currentDegree++;
			else if (isEdgeFuture(vr, vc - 1, true, curR, curC, curIsH)) potentialEdges++;
		}
		if (vc < colunas) {
			if (h[vr][vc]) currentDegree++;
			else if (isEdgeFuture(vr, vc, true, curR, curC, curIsH)) potentialEdges++;
		}

		if (currentDegree > 2) return false;
		// Se o grau é 1, ele PRECISA de pelo menos mais uma aresta no futuro para chegar a 2
		if (currentDegree == 1 && potentialEdges == 0) return false;
		// Se o grau é 0 e não há potencial para chegar a 2, ele deve obrigatoriamente ficar em 0 (poda)
		// (Isso é tratado naturalmente, mas ajuda a podar se houver apenas 1 potencial restante)
		if (currentDegree == 0 && potentialEdges == 1) return true; 

		return true;
	}

	/**
	 * Determina se uma aresta específica será processada em um momento posterior
	 * na ordem de varredura do backtracking.
	 * 
	 * @param er Linha da aresta alvo.
	 * @param ec Coluna da aresta alvo.
	 * @param isH Tipo da aresta alvo.
	 * @param curR Linha da aresta atual.
	 * @param curC Coluna da aresta atual.
	 * @param curIsH Tipo da aresta atual.
	 * @return true se a aresta alvo for "futuro".
	 */
	private boolean isEdgeFuture(int er, int ec, boolean isH, int curR, int curC, boolean curIsH) {
		if (curIsH == isH) return isH ? (ec > curC || er > curR) : (er > curR || (er == curR && ec > curC));
		return !curIsH; // Se estamos em V, H é passado. Se estamos em H, V é futuro.
	}

	/**
	 * Valida as restrições numéricas de uma célula específica. Verifica se o número de
	 * arestas presentes não excede a pista e, se a célula estiver concluída, se é igual.
	 * 
	 * @param puz Matriz de pistas.
	 * @param h Arestas horizontais.
	 * @param v Arestas verticais.
	 * @param i Linha da célula.
	 * @param j Coluna da célula.
	 * @param r Linha da aresta atual.
	 * @param c Coluna da aresta atual.
	 * @param isH Tipo da aresta atual.
	 * @return true se a célula respeitar as restrições.
	 */
	private boolean checkCellConstraints(int[][] puz, boolean[][] h, boolean[][] v, int i, int j, int r, int c, boolean isH) {
		if (puz[i][j] == -1) return true;
		int edges = (h[i][j] ? 1 : 0) + (h[i + 1][j] ? 1 : 0) + (v[i][j] ? 1 : 0) + (v[i][j + 1] ? 1 : 0);
		
		// Regra básica: não pode ter mais arestas que a pista
		if (edges > puz[i][j]) return false;
		
		if (!isH && r == i && c == j + 1) { // Se a célula está "concluída" pela ordem de varredura
			return edges == puz[i][j];
		}
		return true;
	}

	/**
	 * Calcula o grau atual (número de arestas presentes) de um vértice específico.
	 * 
	 * @param h Arestas horizontais.
	 * @param v Arestas verticais.
	 * @param r Linha do vértice.
	 * @param c Coluna do vértice.
	 * @return O grau do vértice (0 a 4).
	 */
	private int getDegree(boolean[][] h, boolean[][] v, int r, int c) {
		int d = 0;
		if (r > 0 && v[r - 1][c]) d++;
		if (r < linhas && v[r][c]) d++;
		if (c > 0 && h[r][c - 1]) d++;
		if (c < colunas && h[r][c]) d++;
		return d;
	}

	/**
	 * Conta quantas pistas numéricas (diferentes de -1) existem no puzzle.
	 * 
	 * @param p Matriz do puzzle.
	 * @return Total de pistas presentes.
	 */
	public int contarPistas(int[][] p) {
		int count = 0;
		for (int i = 0; i < linhas; i++) {
			for (int j = 0; j < colunas; j++) {
				if (p[i][j] != -1) count++;
			}
		}
		return count;
	}

	/**
	 * Estrutura de dados Disjoint Set Union (DSU) customizada.
	 * Utilizada para validar a conectividade global da solução, garantindo que todas as
	 * arestas ativas pertençam a um único componente conexo (o loop principal).
	 */
	// Classe Interna DSU para gerenciamento de conectividade com suporte a rollback
	class DSU {
		int[] parent;
		int[] rank;
		int[] degree; 
		int numComponents; // Rastreia apenas componentes que possuem arestas (fragmentos do loop).
		Deque<int[]> history = new ArrayDeque<>();

		DSU(int n) {
			parent = new int[n];
			rank = new int[n];
			degree = new int[n]; // Mantido para compatibilidade com testarSolucao e para rastrear o grau
			numComponents = n;
			for (int i = 0; i < n; i++) parent[i] = i;
		}

		/**
		 * Busca iterativa para evitar StackOverflow em grids grandes (ex: 30x30).
         * Não utiliza compressão de caminho para permitir Rollback perfeito durante o Backtracking.
		 */
		int find(int i) {
			while (parent[i] != i) {
				i = parent[i];
			}
			return i;
		}

		/** 
		 * Une dois vértices através de uma aresta.
         * Armazena o estado anterior no 'history' para permitir a reversão (backtrack).
         * @return true se um ciclo foi detectado (vértices já conectados).
		 */
		void union(int i, int j) {
			degree[i]++; degree[j]++;
			int rootI = find(i);
			int rootJ = find(j);
			if (rootI != rootJ) { // Se os vértices não estão no mesmo componente, realiza a união
				numComponents--; // Um componente a menos
				if (rank[rootI] < rank[rootJ]) {
					history.push(new int[]{rootI, rootJ, 0, i, j}); // Registra a união (rootI -> rootJ, rank não muda)
					parent[rootI] = rootJ;
				} else {
					int rankChanged = (rank[rootI] == rank[rootJ] ? 1 : 0);
					history.push(new int[]{rootJ, rootI, rankChanged, i, j}); // Registra a união (rootJ -> rootI, rank pode mudar)
					parent[rootJ] = rootI;
					if (rankChanged == 1) rank[rootI]++;
				}
			} else { // Se os vértices já estão no mesmo componente, esta aresta fecha um ciclo
				history.push(new int[]{-1, -1, 0, i, j}); // Sinaliza que um ciclo foi detectado (sem união real)
			}
		}

		/**
		 * Reverte a última operação de união, restaurando o estado de conectividade global.
         * Essencial para a busca por força bruta retornar ao estado anterior do nó.
		 */
		void rollback() {
			int[] last = history.pop();
			degree[last[3]]--; // Desfaz o incremento do grau do vértice 1
			degree[last[4]]--; // Desfaz o incremento do grau do vértice 2
			if (last[0] != -1) { // Se a última operação foi uma união real (não um ciclo)
				numComponents++; // Restaura o número de componentes
				parent[last[0]] = last[0];
				if (last[2] == 1) rank[last[1]]--;
			}
		}
	}

	/**
	 * Retorna o último relatório gerado pela validação da solução.
	 *
	 * @return String contendo a mensagem de status da solução.
	 */
	String relatorio() {
		return strRelatorio;
	}

	/**
	 * Preenche as matrizes de solução h e v com base nos estados finais deduzidos pelo Analyzer.
	 * 
	 * @param h Matriz horizontal a ser preenchida.
	 * @param v Matriz vertical a ser preenchida.
	 */
	public void mostrarSolucao(boolean[][] h, boolean[][] v) {
		int i, j;
		Analyzer.analyze();
		for(i = 0; i < linhas; i++)
			for(j = 0; j <= colunas; j++)
				v[i][j] = sb.ve[i][j].state == EdgeState.present;
		for(i = 0; i <= linhas; i++)
			for(j = 0; j < colunas; j++)
				h[i][j] = sb.he[i][j].state == EdgeState.present;
	}
	
	/**
	 * Prepara um mapa de dados contendo todas as informações do puzzle,
	 * pronto para ser serializado para JSON.
	 */
	public Map<String, Object> exportarParaMap() {
		Map<String, Object> export = new LinkedHashMap<>();
		
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("dificuldade", lastNivel != null ? lastNivel.name().toUpperCase() : "UNKNOWN");
		metadata.put("seed", lastSeed);
		metadata.put("densidade_dicas", (double)contarPistas(puzzle) / (linhas * colunas));
		metadata.put("tamanho", Map.of("linhas", linhas, "colunas", colunas));
		
		export.put("metadata", metadata);
		export.put("grid", puzzle);
		
		boolean[][] h = new boolean[linhas + 1][colunas];
		boolean[][] v = new boolean[linhas][colunas + 1];
		mostrarSolucao(h, v);
		
		Map<String, Object> solucao = new LinkedHashMap<>();
		solucao.put("horizontais", h);
		solucao.put("verticais", v);
		export.put("solucao", solucao);
		
		return export;
	}

	/**
	 * Gera um puzzle com os parâmetros especificados e o exporta para um arquivo JSON.
	 * O arquivo será salvo na pasta "json" no diretório de execução.
	 *
	 * @param l Linhas do puzzle.
	 * @param c Colunas do puzzle.
	 * @param nivel Dificuldade do puzzle.
	 * @param seed Seed para a geração do puzzle.
	 * @throws IOException Se ocorrer um erro ao escrever o arquivo.
	 */
	public void exportPuzzleToJsonFile(int l, int c, Dificuldade nivel, int seed) throws IOException {
		// 1. Gerar o puzzle
		criarPuzzle(l, c, nivel, seed);

		// 2. Obter os dados do puzzle em formato Map
		Map<String, Object> puzzleData = exportarParaMap();

		// 3. Configurar o ObjectMapper do Jackson
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT); // Para JSON formatado

		// 4. Definir o diretório e nome do arquivo
		String jsonDir = "json";
		Files.createDirectories(Paths.get(jsonDir)); // Cria o diretório se não existir

		String filename = String.format("puzzle_%s_%d_%dx%d.json", nivel.name().toLowerCase(), seed, l, c);
		File outputFile = new File(jsonDir, filename);

		// 5. Escrever o Map para o arquivo JSON
		objectMapper.writeValue(outputFile, puzzleData);

		System.out.println("Puzzle exportado para: " + outputFile.getAbsolutePath());

		// 6. Validar o arquivo gerado
		validarContraSchema(outputFile);
	}

	/**
	 * Valida um arquivo JSON contra o esquema pré-definido do Slitherlink.
	 * @param jsonFile O arquivo a ser validado.
	 */
	public void validarContraSchema(File jsonFile) {
		String schemaStr = "{\n" +
			"  \"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
			"  \"type\": \"object\",\n" +
			"  \"properties\": {\n" +
			"    \"metadata\": {\n" +
			"      \"type\": \"object\",\n" +
			"      \"properties\": {\n" +
			"        \"dificuldade\": { \"type\": \"string\", \"enum\": [\"EASY\", \"NORMAL\", \"HARD\", \"EXPERT\"] },\n" +
			"        \"seed\": { \"type\": \"integer\" },\n" +
			"        \"densidade_dicas\": { \"type\": \"number\" },\n" +
			"        \"tamanho\": {\n" +
			"          \"type\": \"object\",\n" +
			"          \"properties\": {\n" +
			"            \"linhas\": { \"type\": \"integer\" },\n" +
			"            \"colunas\": { \"type\": \"integer\" }\n" +
			"          },\n" +
			"          \"required\": [\"linhas\", \"colunas\"]\n" +
			"        }\n" +
			"      },\n" +
			"      \"required\": [\"dificuldade\", \"seed\", \"densidade_dicas\", \"tamanho\"]\n" +
			"    },\n" +
			"    \"grid\": {\n" +
			"      \"type\": \"array\",\n" +
			"      \"items\": { \"type\": \"array\", \"items\": { \"type\": \"integer\" } }\n" +
			"    },\n" +
			"    \"solucao\": {\n" +
			"      \"type\": \"object\",\n" +
			"      \"properties\": {\n" +
			"        \"horizontais\": { \"type\": \"array\" },\n" +
			"        \"verticais\": { \"type\": \"array\" }\n" +
			"      },\n" +
			"      \"required\": [\"horizontais\", \"verticais\"]\n" +
			"    }\n" +
			"  },\n" +
			"  \"required\": [\"metadata\", \"grid\", \"solucao\"]\n" +
			"}";

		try {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode jsonNode = mapper.readTree(jsonFile);
			
			JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
			JsonSchema schema = factory.getSchema(schemaStr);

			Set<ValidationMessage> errors = schema.validate(jsonNode);

			if (errors.isEmpty()) {
				System.out.println("JSON validado com sucesso contra o Schema.");
			} else {
				System.err.println("Erros de validação encontrados no JSON:");
				errors.forEach(err -> System.err.println("- " + err.getMessage()));
			}
		} catch (IOException e) {
			System.err.println("Erro ao ler arquivo para validação: " + e.getMessage());
		}
	}

	/**
	 * Imprime uma representação textual da grade e das arestas da solução no console.
	 * 
	 * @param h Estados das arestas horizontais (true para presente, false para ausente).
	 * @param v Estados das arestas verticais.
	 */
	void print(boolean[][] h, boolean[][] v) {
		int i, j;
		for(j = 0; j < colunas; j++)
			if(h[0][j])
				System.out.print(" _");
			else // Aresta horizontal superior
				System.out.print("  ");
		System.out.println(" ");
		for(i = 0; i < linhas; i++) {
			for(j = 0; j < colunas; j++) {
				if(v[i][j])
					System.out.print("|");
				else
					System.out.print(" "); // Aresta vertical esquerda
				if(h[i + 1][j])
					System.out.print("_");
				else
					System.out.print(" "); // Aresta horizontal inferior
			}
			if(v[i][colunas]) // Aresta vertical direita (última coluna)
				System.out.println("|");
			else
				System.out.println(" ");
		}
		
	}
		
	/**
	 * Método de entrada para execução da simulação de performance em diferentes tamanhos de grid.
	 * @param args Argumentos da linha de comando.
	 */
	public static void main(String[] args) {
		PuzzleEngine solver = new PuzzleEngine();

		// Parâmetros para a geração do puzzle conforme solicitado
		int linhas = 34;
		int colunas = 13;
		Dificuldade dificuldade = Dificuldade.EXPERT;
		int seed = 42;

		try {
			solver.exportPuzzleToJsonFile(linhas, colunas, dificuldade, seed);
		} catch (IOException e) {
			System.err.println("Erro ao exportar o puzzle para JSON: " + e.getMessage());
			e.printStackTrace();
		}

		// O código de simulação de performance original pode ser mantido ou removido,
		// dependendo se o usuário quer apenas a exportação ou a simulação também.
		// Por enquanto, o código de simulação foi comentado para focar na exportação.
		/*
		int[][] dimensoes = {{10, 10}, {15, 30}, {30, 30}};
		System.out.println("\nIniciando Simulação de Performance (Nível: " + dificuldade + ")");
		System.out.println("---------------------------------------------------------");

		for (int[] dim : dimensoes) {
			int l = dim[0];
			int c = dim[1];
			long startTime = System.currentTimeMillis();
			solver.criarPuzzle(l, c, dificuldade, 42); // Usando o mesmo seed para consistência
			long endTime = System.currentTimeMillis();
			int pistasRestantes = solver.contarPistas(solver.puzzle);
			System.out.printf("Tamanho: %dx%d | Tempo: %dms | Pistas: %d | Nós BT: %d\n",
				l, c, (endTime - startTime), pistasRestantes, solver.totalNodes.get());
			solver.printLoop();
			solver.printPuzzle();
			solver.sb.print(System.out);
			boolean[][] h = new boolean[l + 1][c];
			boolean[][] v = new boolean[l][c + 1];
			solver.mostrarSolucao(h, v);
			solver.print(h, v);
		}
		System.out.println("---------------------------------------------------------");
		System.out.println("Simulação concluída.");
		*/
	}

	/**
	 * Classe utilitária para servir como chave no cache de soluções.
	 * Implementa hashCode e equals baseados no conteúdo da matriz do puzzle.
	 */
	    private static class PuzzleKey {
        private final int[][] data;
        private final int hash;
        PuzzleKey(int[][] board, boolean shouldClone) {
            if (shouldClone) { this.data = new int[board.length][]; for (int i = 0; i < board.length; i++) this.data[i] = board[i].clone(); }
            else this.data = board;
            this.hash = Arrays.deepHashCode(this.data);
        }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof PuzzleKey)) return false; return Arrays.deepEquals(this.data, ((PuzzleKey) o).data); }
        @Override public int hashCode() { return hash; }
    }
}
/**
 * Motor de dedução lógica e propagação de restrições.
 * Gerencia uma fila de análise para processar elementos (células e vértices) cujas
 * possibilidades foram reduzidas, permitindo resolver partes do puzzle por inferência.
 */
class Analyzer {
	/** 
	 * O Analyzer utiliza propagação de restrições (Constraint Propagation).
     * O AnalyzerState é mantido via ThreadLocal para permitir que múltiplas
     * instâncias de redução rodem em paralelo sem corromper a fila de processamento.
	 */
	private static class AnalyzerState {
        // Bitmask para configurações válidas de arestas em um Elemento (Cell ou Vertex).
		long[] registered = new long[1 << Element.MAXN]; 
		long timeStamp = 0;
		Element[] open = new Element[200000]; // Worklist/Fila de elementos para re-análise.
		int nOpen = 0;
	}
	
	private static final ThreadLocal<AnalyzerState> state = ThreadLocal.withInitial(AnalyzerState::new); // Garante thread-safety

	/**
	 * Agenda um elemento (célula ou vértice) para análise lógica.
	 * @param e Elemento a ser agendado.
	 */
	static void schedule(Element e) {
		AnalyzerState s = state.get();
		s.open[s.nOpen++] = e;
	}
	
	/** 
	 * Ciclo principal de dedução: Enquanto houver mudanças, propaga restrições para vizinhos.
	 */
	static void analyze() {
		AnalyzerState s = state.get();
		while(s.nOpen > 0)
			s.open[--s.nOpen].analyze();
	}
	
	/** 
	 * Inicia uma nova fase de comparação lógica, incrementando o timestamp global.
	 */
	static void startComparison() {
		state.get().timeStamp++;
	}
	
	/**
	 * Registra uma máscara de bits como uma configuração válida no timestamp atual para a thread.
	 * @param c Configuração de arestas (bits).
	 */
	static void registerValidConfiguration(int c) {
		AnalyzerState s = state.get();
		s.registered[c] = s.timeStamp;
	}
	
	/** 
	 * Verifica se uma configuração de arestas é inválida com base no timestamp de registro.
	 * @param c Configuração de arestas.
	 * @return true se for inválida.
	 */
	static boolean isInvalid(int c) {
		AnalyzerState s = state.get();
		return s.registered[c] < s.timeStamp;
	}
}

/**
 * Utilitário para geração de combinações de bits.
 * Calcula todas as configurações possíveis de arestas para um elemento com base
 * no número de vizinhos e nas restrições da pista.
 */
class CombGenerator {
	/** 
	 * Mantém o estado isolado da geração de combinações para cada thread.
	 */
	private static class State {
		int k, n, m;
		int[] element = new int[32];
		int[] bit = new int[32];
		int[] comb;
	}

	private static final ThreadLocal<State> state = ThreadLocal.withInitial(State::new); // Garante thread-safety

	/**
	 * Calcula o coeficiente binomial (n escolha m).
	 * @param n Total de elementos.
	 * @param m Elementos a escolher.
	 * @return O valor do coeficiente binomial.
	 */
	static int binCoef(int n, int m) {
		int i, j, nComb = 1;
		for(i = 1, j = n; i <= m; i++, j--)
			nComb = nComb * j / i;
		return nComb;
	}
	
	/** 
	 * Constrói recursivamente combinações de bits.
	 * @param s Estado do gerador para a thread atual.
	 * @param pos Posição atual na combinação.
	 * @param minValue Valor mínimo para a próxima escolha.
	 */
	private static void buildComb(State s, int pos, int minValue) {
		int i, maxValue;
		if(pos == s.m) {
			int res = 0;
			for(i = 0; i < s.m; i++)
				res = res | s.bit[s.element[i]];
			s.comb[s.k++] = res;
			return;
		}
		maxValue = s.n - s.m + pos;
		for(i = minValue; i <= maxValue; i++) {
			s.element[pos] = i;
			buildComb(s, pos + 1, i + 1);			
		}
	}
	
	/** 
	 * Gera todas as combinações de bits válidas para um conjunto de arestas.
	 * 
	 * @param comb Array para armazenar as combinações geradas.
	 * @param bits Máscara de bits representando as arestas disponíveis.
	 * @param m Quantidade de arestas que devem estar presentes.
	 * @param hasZero Se verdadeiro, inclui a configuração onde nenhuma aresta está presente (usado em vértices).
	 * @return O número total de combinações geradas.
	 */
	static int combinations(int[] comb, int bits, int m, boolean hasZero) {
		int nComb, i, j, b;
		State s = state.get();
		s.comb = comb;
		s.m = m;

		i = 1;
		s.n = 0;
		while(i <= bits && i > 0) { // Itera sobre os bits para contar 'n' (número de bits setados)
			if((i & bits) > 0)
				s.n++;
			i <<= 1;
		}
		j = 0;
		i = 1;
		while(i <= bits && i > 0) {
			if((i & bits) > 0) // Preenche o array 'bit' com os valores dos bits setados
				s.bit[j++] = i;
			i <<= 1;
		}
		if(s.m >= 0) {
			nComb = binCoef(s.n, s.m);
			if(hasZero)
				nComb++;
			s.k = 0;
			buildComb(s, 0, 0); // Inicia a construção recursiva das combinações
			if(hasZero)
				comb[nComb - 1] = 0;
		}
		else {
			nComb = 1 << s.n;
			for(i = 0; i < nComb; i++) {
				b = 0;
				for(j = 0; j < s.n; j++) // Gera todas as combinações possíveis (caso m < 0)
					if((i & (1 << j)) != 0)
						b |= s.bit[j];
				comb[i] = b;				
			}
		}
		return nComb;
	}
}

/**
 * Classe base abstrata para componentes que participam da análise lógica.
 * Mantém o estado de possibilidades (poss) via bitmasks e gerencia a comunicação
 * com o Analyzer quando mudanças de estado ocorrem nas arestas adjacentes.
 */
class Element {
	static final int MAXN = 4, MAXP = 1 << MAXN;
	
	Edge [] ed = new Edge[MAXN];
	Element [] el = new Element[MAXN];
	int [] poss = new int[MAXP];
	int n, nPoss;
	boolean isOpen; // indicates if this Element is already scheduled for analysis. 
	
	/**
	 * Inicializa o elemento vinculando-o às suas arestas e vizinhos.
	 * @param edp Arestas incidentes.
	 * @param elp Elementos vizinhos.
	 */
	void init(Edge[] edp, Element[] elp) {
		int i;
		n = 0;
		for(i = 0; i < edp.length; i++)
			if(edp[i] != null) {
				edp[n] = edp[i];
				elp[n] = elp[i];
				n++;
			}
		for(i = 0; i < n; i++) {
			ed[i] = edp[i];
			el[i] = elp[i];
		}
	}
	
	/**
	 * Adiciona o elemento à fila de processamento do Analyzer se ele possuir possibilidades a filtrar.
	 */
	void scheduleForAnalysis() {
		if(nPoss > 0 && !isOpen) {
			isOpen = true;
			Analyzer.schedule(this);
		}
	}
	
	/**
	 * Informa ao Analyzer quais configurações de arestas são suportadas por este elemento.
	 * @param edges Máscara de bits das arestas a serem analisadas.
	 */
	void supportAnalysis(int edges) { // the bits in edges indicate which Edges will be analyzed
			for(int i = 0; i < nPoss; i++)
				Analyzer.registerValidConfiguration(poss[i] & edges);		
	}
	
	/**
	 * Filtra as possibilidades do elemento com base nas restrições impostas pelos vizinhos.
	 */
	void analyze() {
		int i, j, k, bits, oldNPoss;
		isOpen = false;
		oldNPoss = nPoss;
		for(i = 0; i < n; i++) {
			if(el[i].nPoss == 0)
				continue; // Se o vizinho não tem possibilidades, não há restrição a propagar
			Analyzer.startComparison();
			j = (i == 0) ? n - 1 : i - 1;
			bits = ed[j].idBit | ed[i].idBit;
			el[i].supportAnalysis(bits);
			k = 0;
			while(k < nPoss) {
				if(Analyzer.isInvalid(poss[k] & bits))
					poss[k] = poss[--nPoss];
				else
					k++;
			}
		}
		if(nPoss == 1) {
			bits = poss[0];
			for(i = 0; i < n; i++) ed[i].state = (ed[i].idBit & bits) != 0 ? EdgeState.present : EdgeState.absent;
		}
		if(oldNPoss > nPoss) { for(i = 0; i < n; i++) el[i].scheduleForAnalysis(); }
	}
}

class Cell extends Element {
	char label;
	void init(Edge[] ee, Vertex[] vv) { super.init(ee, vv); nPoss = 0; label = ' '; }
	void init() { n = 0; nPoss = 0; label = ' '; }
	void initPoss(int num) {
		int bits = 0; for(int i=0; i<n; i++) bits |= ed[i].idBit;
		label = (num >= 0) ? (char)('0'+num) : ' ';
		nPoss = CombGenerator.combinations(poss, bits, num, false);
		scheduleForAnalysis();
	}
}

class Vertex extends Element {
	void init(Edge [] ee, Cell [] cc) {
		super.init(ee, cc); int bits = 0; for(int i=0; i<n; i++) bits |= ed[i].idBit;
		nPoss = CombGenerator.combinations(poss, bits, 2, true);
		scheduleForAnalysis();
	}
	boolean used() { for(int i=0; i<nPoss; i++) if(poss[i]==0) return false; return true; }
}

class EdgeState { static final int unknown = 0, present = 1, absent = 2; }

class Edge {
	int idBit;
	int state;
	Edge(int id) { idBit = id; state = EdgeState.unknown; }
}

class SquareBoard {
	static final int MAXS = PuzzleEngine.MAXS;
	Cell[][] s = new Cell[MAXS][MAXS];
	Cell outerCell;
	Edge[][] ve = new Edge[MAXS][MAXS + 1], he = new Edge[MAXS + 1][MAXS];
	Vertex [][] v = new Vertex[MAXS + 1][MAXS + 1];
	int r, c;
	
	SquareBoard() {
		int i, j;
		for(i = 0; i < MAXS; i++)
			for(j = 0; j < MAXS; j++)
				s[i][j] = new Cell();
		for(i = 0; i < MAXS + 1; i++)
			for(j = 0; j < MAXS + 1; j++)
				v[i][j] = new Vertex();
		for(i = 0; i < MAXS; i++)
			for(j = 0; j < MAXS + 1; j++)
				ve[i][j] = new Edge(((i + j) & 1) == 0 ? 1 : 2);
		for(i = 0; i < MAXS + 1; i++)
			for(j = 0; j < MAXS; j++)
				he[i][j] = new Edge(((i + j) & 1) == 0 ? 4 : 8);
	}
	
	void init(int r, int c) {
		int i, j;
		if (this.r == r && this.c == c) {
			for(i = 0; i <= r; i++) 
				for(j = 0; j < c; j++) he[i][j].state = EdgeState.unknown;
			for(i = 0; i < r; i++) 
				for(j = 0; j <= c; j++) ve[i][j].state = EdgeState.unknown;
			
			outerCell.isOpen = false; 
			outerCell.nPoss = 0; 
			
			for(i = 0; i <= r; i++) 
				for(j = 0; j <= c; j++) {
					v[i][j].isOpen = false; 
					int bits = 0; 
					for(int k = 0; k < v[i][j].n; k++) bits |= v[i][j].ed[k].idBit;
					v[i][j].nPoss = CombGenerator.combinations(v[i][j].poss, bits, 2, true);
					v[i][j].scheduleForAnalysis();
				}
			for(i = 0; i < r; i++)
				for(j = 0; j < c; j++) {
					s[i][j].isOpen = false;
					s[i][j].label = ' ';
					s[i][j].nPoss = 0;
				}
			return;
		}

		Cell[] auxCells = new Cell[4]; 
		Edge[] auxEdges = new Edge[4];
		Vertex[] auxVertices = new Vertex[4];
		this.r = r;
		this.c = c;
		outerCell = new Cell();
		outerCell.init();
		for(i = 0; i < r; i++)
			for(j = 0; j < c; j++) {
				auxVertices[0] = v[i][j];
				auxVertices[1] = v[i][j + 1];
				auxVertices[2] = v[i + 1][j + 1];
				auxVertices[3] = v[i + 1][j];
				auxEdges[0] = he[i][j];
				auxEdges[1] = ve[i][j + 1];
				auxEdges[2] = he[i + 1][j];
				auxEdges[3] = ve[i][j];
				s[i][j].init(auxEdges, auxVertices);
			}
		for(i = 0; i <= r; i++)
			for(j = 0; j <= c; j++) {
				if(i > 0) {
					auxCells[0] = (j > 0) ? s[i - 1][j - 1] : outerCell;
					auxCells[1] = (j < c) ? s[i - 1][j] : outerCell;
				}
				else
					auxCells[0] = auxCells[1] = outerCell;
				if(i < r) {
					auxCells[2] = (j < c) ? s[i][j] : outerCell;
					auxCells[3] = (j > 0) ? s[i][j - 1] : outerCell;
				}
				else
					auxCells[2] = auxCells[3] = outerCell;
				auxEdges[0] = (i > 0) ? ve[i - 1][j] : null;
				auxEdges[1] = (j < c) ? he[i][j] : null;
				auxEdges[2] = (i < r) ? ve[i][j] : null;
				auxEdges[3] = (j > 0) ? he[i][j - 1] : null;
				v[i][j].init(auxEdges, auxCells);
			}		
	}
	
	void init(int[][] board) {
		int rows = board.length;
		int cols = (rows > 0) ? board[0].length : 0;
		init(rows, cols);
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) s[i][j].initPoss(board[i][j]);
		}
	}
	
	boolean isSolved() {
		for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) if (s[i][j].nPoss != 1) return false;
		return true;
	}

	void buildLine1(int x, char[] buffer) {
		char aux;
		buffer[0] = '#';
		buffer[1] = ' ';
		int p = 2;
		int i = 0; 
		while(true) {
			if(!v[x][i].used())
				buffer[p++] = ' ';
			else
				switch(v[x][i].poss[0]) {
				case 3: buffer[p++] = '|'; break; 
				case 12: buffer[p++] = '-'; break;
				default: buffer[p++] = '+';
				}
			if(i == c)
				break;
			if(he[x][i].state == EdgeState.present)
				aux = '-';
			else
				aux = ' ';
			buffer[p++] = aux; // Aresta horizontal
			buffer[p++] = aux;
			buffer[p++] = aux;
			i++;
		}
		buffer[p++] = ' ';
		buffer[p++] = '#';
	}
	
	void buildLine2(int x, char[] buffer) {
		buffer[0] = '#';
		buffer[1] = ' ';
		int p = 2;
		int i = 0; 
		while(true) {
			if(ve[x][i].state == EdgeState.present)
				buffer[p++] = '|';
			else buffer[p++] = ' ';
			if(i == c) 
				break;
			buffer[p++] = ' ';
			buffer[p++] = s[x][i].label;
			buffer[p++] = ' ';
			i++;
		}
		buffer[p++] = ' ';
		buffer[p++] = '#';
	}
	
	void print(java.io.PrintStream out) {
		char[] buffer = new char[MAXS * 4 + 50]; // Buffer dinâmico baseado no limite máximo
		String line, b1, b2;
		int i, len = c * 4 + 5;
		for(i = 0; i < len; i++)
			buffer[i] = '#';
		b1 = new String(buffer, 0, len);
		for(i = 1; i < len - 1; i++)
			buffer[i] = ' ';
		b2 = new String(buffer, 0, len);
		out.println(b1);
		out.println(b2);
		i = 0;
		while(true) {
			buildLine1(i, buffer);
			line = new String(buffer, 0, len);
			out.println(line);
			if(i == r)
				break;
			buildLine2(i, buffer);
			line = new String(buffer, 0, len);
			out.println(line);
			i++;
		}
		out.println(b2);
		out.println(b1);
	}
}
