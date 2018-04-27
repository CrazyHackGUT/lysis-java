package lysis;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.util.LinkedList;

import lysis.builder.MethodParser;
import lysis.builder.SourceBuilder;
import lysis.builder.structure.ControlBlock;
import lysis.builder.structure.SourceStructureBuilder;
import lysis.lstructure.Argument;
import lysis.lstructure.Function;
import lysis.lstructure.LGraph;
import lysis.lstructure.VariableType;
import lysis.nodes.NodeAnalysis;
import lysis.nodes.NodeBlock;
import lysis.nodes.NodeBuilder;
import lysis.nodes.NodeGraph;
import lysis.nodes.NodeRenamer;
import lysis.nodes.NodeRewriter;
import lysis.sourcepawn.SourcePawnFile;
import lysis.types.BackwardTypePropagation;
import lysis.types.ForwardTypePropagation;

public class Lysis {

    public static final boolean bDebug = false;

    static void PreprocessMethod(SourcePawnFile file, Function func) throws Exception
    {
        MethodParser mp = new MethodParser(file, func.address());
        LGraph graph = mp.parse();
        
        // This function had no debug info attached :(
        if (func.codeEnd() == file.code().bytes().length)
        	func.setCodeEnd(mp.getExitPC()-4);
        
        // No argument information for this function :(
        if (func.args() == null || func.args().length < graph.nargs)
        {
            LinkedList<Argument> args = new LinkedList<Argument>();
            int start = 0;
            int num = graph.nargs;
            if (func.args() != null)
            {
                start = func.args().length;
                // Copy present args
                for (Argument arg: func.args())
                    args.add(arg);
            }
            for (int i=start; i<num; i++)
            {
                Argument arg = new Argument(VariableType.Normal, "_arg" + i, 0, null, null);
                args.add(arg);
                
                file.addArgumentVar(func, i);
            }
            func.setArguments(args);
        }
    }
    
    static void DumpMethod(SourcePawnFile file, SourceBuilder source, long addr) throws Exception
    {
        MethodParser mp = new MethodParser(file, addr);
        LGraph graph = mp.parse();
        //DebugSpew.DumpGraph(graph.blocks, new DataOutputStream(System.out));

        NodeBuilder nb = new NodeBuilder(file, graph);
        NodeBlock[] nblocks = nb.buildNodes();

        NodeGraph ngraph = new NodeGraph(file, nblocks);

        // Remove dead phis first.
        NodeAnalysis.RemoveDeadCode(ngraph);
        
        NodeRewriter rewriter = new NodeRewriter(ngraph);
        rewriter.rewrite();

        NodeAnalysis.CollapseArrayReferences(ngraph);

        // Propagate type information.
        ForwardTypePropagation ftypes = new ForwardTypePropagation(ngraph);
        ftypes.propagate();

        BackwardTypePropagation btypes = new BackwardTypePropagation(ngraph);
        btypes.propagate();

        // We're not fixpoint, so just iterate again.
        ftypes.propagate();
        btypes.propagate();

        // Try this again now that we have type information.
        NodeAnalysis.CollapseArrayReferences(ngraph);

        ftypes.propagate();
        btypes.propagate();

        // Coalesce x[y] = x[y] + 5 into x[y] += 5
        NodeAnalysis.CoalesceLoadStores(ngraph);
        
        // Print string initializations new String:bla[] = "HALLO";
        NodeAnalysis.HandleMemCopys(ngraph);

        // After this, it is not legal to run type analysis again, because
        // arguments expecting references may have been rewritten to take
        // constants, for pretty-printing.
        NodeAnalysis.AnalyzeHeapUsage(ngraph);

        // Do another DCE pass, this time, without guards.
        NodeAnalysis.RemoveGuards(ngraph);
        NodeAnalysis.RemoveDeadCode(ngraph);

        NodeRenamer renamer = new NodeRenamer(ngraph);
        renamer.rename();

        // Do a pass to coalesce declaration+stores.
        NodeAnalysis.CoalesceLoadsAndDeclarations(ngraph);

        // Simplify conditional expressions.
        // BlockAnalysis.NormalizeConditionals(ngraph);
        SourceStructureBuilder sb = new SourceStructureBuilder(ngraph);
        ControlBlock structure = sb.build();
        
        source.write(structure);

        //System.in.read();
        //System.in.read();
    }
	
	static Function FunctionByName(SourcePawnFile file, String name)
    {
        for (int i = 0; i < file.functions().length; i++)
        {
            if (file.functions()[i].name() == name)
                return file.functions()[i];
        }
        return null;
    }
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1)
        {
            System.err.println("usage: <file.smx> or <file.amxx> [output]");
            return;
        }
		
		PrintStream sysout;
		try {
			sysout = new PrintStream(System.out, true, "UTF-8");
			System.setOut(sysout);
		} catch (UnsupportedEncodingException e2) {
			e2.printStackTrace();
		}
		
		String path = args[0];
		PrintStream to = System.out;
		if (args.length == 2) {
		    try {
                File hFile = new File(args[1]);
                hFile.createNewFile();
                hFile.setWritable(true);
                to = new PrintStream(hFile);
            } catch (IOException e) {
		        System.err.println("Exception reported when opening the file (IOException): " + e.getMessage());
		        return;
            }
        }


        PawnFile file = null;
		try {
			file = PawnFile.FromFile(path);
		} catch (Exception e1) {
			e1.printStackTrace();
			return;
		}
        
		if(file == null) {
			System.err.println("Failed to parse file.");
			return;
		}
		
		//DataOutputStream dOut = new DataOutputStream(System.out);
		
        // Parse methods for calls and globals which don't have debug info attached.
        for (int i = 0; i < file.functions().length; i++)
        {
            Function fun = file.functions()[i];
            try
            {
                PreprocessMethod((SourcePawnFile)file, fun);
            }
            catch (Throwable e)
            {
                e.printStackTrace();
                to.println("");
                to.println("/* ERROR PREPROCESSING! " + e.getMessage() + " */");
                to.println("// function \"" + fun.name() + "\" (number " + i + ")");
            }
        }
		
        SourceBuilder source = new SourceBuilder(file, to);
        try {
			source.writeGlobals();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

        for (int i = 0; i < file.functions().length; i++)
        {
            Function fun = file.functions()[i];
//#if 
            try
            {
                DumpMethod((SourcePawnFile)file, source, fun.address());
                to.println("");
            }
            catch (Throwable e)
            {
            	e.printStackTrace();
                to.println("");
                to.println("/* ERROR! " + e.getMessage() + " */");
                to.println(" function \"" + fun.name() + "\" (number " + i + ")");
                source = new SourceBuilder((SourcePawnFile)file, to);
            }
//#endif
        }
	}
}
