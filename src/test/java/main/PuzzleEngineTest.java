package test.java.main;

import org.junit.jupiter.api.Test;

import main.java.main.PuzzleEngine;

import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

public class PuzzleEngineTest {

    @Test
    @DisplayName("Deve gerar um puzzle com solução única e respeitar as dimensões")
    void testGeracaoPuzzle() {
        PuzzleEngine engine = new PuzzleEngine();
        int linhas = 10;
        int colunas = 10;
        
        int[][] grid = engine.criarPuzzle(linhas, colunas, PuzzleEngine.Dificuldade.NORMAL, 123);
        
        assertNotNull(grid);
        assertEquals(linhas, grid.length);
        assertEquals(colunas, grid[0].length);
        
        int solucoes = engine.countSolutions(grid);
        assertEquals(1, solucoes, "O puzzle gerado deve ter exatamente uma solução.");
    }
}