#!/bin/bash
# the path to the interpreter and all of the originally intended arguments
args=("$@")

# remove any argument that matches AWS_LAMBDA_EXEC_WRAPPER_EXCLUDE_ARGS
for index in "${!args[@]}"; do
  if [[ ${args[$index]} == *$AWS_LAMBDA_EXEC_WRAPPER_EXCLUDE_ARGS* ]]; then
    unset args[$index]
  fi
done

# insert the extra options
# args=("${args[@]:0:$#-1}" "${extra_args[@]}" "${args[@]: -1}")

# start the runtime with the extra options
exec "${args[@]}"